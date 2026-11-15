package com.ok.wallet.application.service;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.adapter.out.persistence.SellerWalletJpaRepository;
import com.ok.wallet.application.dto.PayoutDepositCheckCommand;
import com.ok.wallet.application.port.in.PayoutDepositCheckUseCase;
import com.ok.wallet.domain.SellerWallet;
import com.ok.wallet.support.KafkaTopicReader;
import com.ok.wallet.support.WalletTestDataCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

// 통합 테스트: 원장을 읽은 답이 정산이 받을 메시지 모양(정산 대사 #308 문서 4-3)으로 실제 Kafka에 나가는지 확인한다.
// 정산 코드는 쓰지 않고, 토픽에 나간 JSON을 문자열 그대로 읽어 필드 이름과 값, 입금 시각의 글자 모양을 본다.
// 입금 시각을 정해 두려고 원장을 SQL로 넣으므로 잔액과 맞지 않아, 끝난 뒤 잔액 검사 없이 지운다
class WalletPayoutDepositCheckServiceIntegrationTest extends AbstractIntegrationTest {
    private static final String CHECKED_TOPIC = "wallet.payout-deposit-checked";

    // 토픽에는 앞 테스트의 메시지가 남으므로 테스트마다 겹치지 않는 묶음·정산 번호를 쓴다
    private static final AtomicLong ID_SEQ = new AtomicLong(8_600_000L);

    @Autowired
    PayoutDepositCheckUseCase payoutDepositCheckUseCase;

    @Autowired
    SellerWalletJpaRepository sellerWalletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    @BeforeEach
    void setUp() {
        new WalletTestDataCleaner(jdbcTemplate).deleteAll();
    }

    @AfterEach
    void tearDown() {
        new WalletTestDataCleaner(jdbcTemplate).deleteAll();
    }

    @Test
    void 원장에_있는_정산과_없는_정산의_답을_묶음_번호_키로_보낸다() {
        // given
        long checkId = nextId();
        long depositedPayoutId = nextId();
        long missingPayoutId = nextId();
        Long walletId = sellerWalletRepository.saveAndFlush(SellerWallet.create(5L)).getId();
        insertPayoutLedger(walletId, depositedPayoutId, 9_700, LocalDateTime.of(2026, 11, 15, 2, 0, 3, 123_456_000));

        // when
        payoutDepositCheckUseCase.check(new PayoutDepositCheckCommand(checkId, List.of(depositedPayoutId, missingPayoutId)));

        // then
        assertThat(new KafkaTopicReader(consumerFactory).valuesWithKey(CHECKED_TOPIC, String.valueOf(checkId), 1))
                .containsExactly("{\"checkId\":" + checkId + ",\"items\":["
                        + "{\"payoutId\":" + depositedPayoutId + ",\"found\":true,\"payeeId\":5,\"amount\":9700,"
                        + "\"depositedAt\":\"2026-11-15T02:00:03.123456\"},"
                        + "{\"payoutId\":" + missingPayoutId + ",\"found\":false,\"payeeId\":null,\"amount\":null,"
                        + "\"depositedAt\":null}]}");
    }

    private void insertPayoutLedger(Long sellerWalletId, long payoutId, long amount, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO seller_wallet_ledger
                    (seller_wallet_id, type, amount, balance_after, reference_key, created_at, updated_at)
                VALUES (?, 'PAYOUT', ?, ?, ?, ?, ?)
                """, sellerWalletId, amount, amount, "PAYOUT:" + payoutId, createdAt, createdAt);
    }

    private static long nextId() {
        return ID_SEQ.incrementAndGet();
    }
}
