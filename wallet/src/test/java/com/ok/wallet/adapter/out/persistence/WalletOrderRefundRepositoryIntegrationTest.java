package com.ok.wallet.adapter.out.persistence;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.application.port.out.WalletOrderRefundRepository;
import com.ok.wallet.domain.WalletOrderRefund;
import com.ok.wallet.domain.WalletOrderRefundFailureReason;
import com.ok.wallet.domain.WalletOrderRefundStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 실제 표(마이그레이션)의 유니크 제약과 잠금 조회까지 확인한다
class WalletOrderRefundRepositoryIntegrationTest extends AbstractIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 15, 10, 0);
    private static final String COMPLETED = WalletOrderRefundStatus.COMPLETED.name();
    private static final String FAILED = WalletOrderRefundStatus.FAILED.name();

    @Autowired
    WalletOrderRefundRepository walletOrderRefundRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    // 반환 기록만 넣고 지갑 잔액은 바꾸지 않아 지갑 정리 도우미의 검사가 필요 없다
    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM wallet_order_refund");
    }

    @Test
    void 처리_기록이_없으면_넣고_1을_돌려준다() {
        // when
        int inserted = inTransaction(() -> walletOrderRefundRepository.insertIfAbsent(
                1L, 10L, 100L, 7_000L, COMPLETED, null, NOW));

        // then
        assertThat(inserted).isOne();
        WalletOrderRefund sut = inTransaction(() -> walletOrderRefundRepository.findForShareByRefundId(1L).orElseThrow());
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getOrderId()).isEqualTo(10L);
            softly.assertThat(sut.getBuyerId()).isEqualTo(100L);
            softly.assertThat(sut.getAmount()).isEqualTo(7_000L);
            softly.assertThat(sut.isCompleted()).isTrue();
            softly.assertThat(sut.getFailureReason()).isNull();
        });
    }

    @Test
    void 거절_기록은_사유와_함께_남는다() {
        // given
        inTransaction(() -> walletOrderRefundRepository.insertIfAbsent(
                1L, 10L, 100L, 7_000L, FAILED, WalletOrderRefundFailureReason.PAYMENT_NOT_FOUND.name(), NOW));

        // when
        WalletOrderRefund sut = inTransaction(() -> walletOrderRefundRepository.findForShareByRefundId(1L).orElseThrow());

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(WalletOrderRefundStatus.FAILED);
            softly.assertThat(sut.getFailureReason()).isEqualTo(WalletOrderRefundFailureReason.PAYMENT_NOT_FOUND);
        });
    }

    @Test
    void 같은_환불의_기록이_있으면_넣지_않고_0을_돌려준다() {
        // given
        inTransaction(() -> walletOrderRefundRepository.insertIfAbsent(1L, 10L, 100L, 7_000L, COMPLETED, null, NOW));

        // when: 같은 환불이 다른 금액·결과로 다시 옴
        int inserted = inTransaction(() -> walletOrderRefundRepository.insertIfAbsent(
                1L, 10L, 100L, 9_000L, FAILED, WalletOrderRefundFailureReason.REFUND_TOTAL_EXCEEDED.name(), NOW));

        // then: 처음 기록이 그대로 남는다
        assertThat(inserted).isZero();
        WalletOrderRefund sut = inTransaction(() -> walletOrderRefundRepository.findForShareByRefundId(1L).orElseThrow());
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getAmount()).isEqualTo(7_000L);
            softly.assertThat(sut.isCompleted()).isTrue();
        });
    }

    @Test
    void 반환_합계는_그_주문에서_돌려준_기록만_더한다() {
        // given: 감자 7,000원·배추 28,000원은 돌려줌, 5,000원은 거절, 다른 주문 1,000원
        inTransaction(() -> {
            walletOrderRefundRepository.insertIfAbsent(1L, 10L, 100L, 7_000L, COMPLETED, null, NOW);
            walletOrderRefundRepository.insertIfAbsent(2L, 10L, 100L, 28_000L, COMPLETED, null, NOW);
            walletOrderRefundRepository.insertIfAbsent(
                    3L, 10L, 100L, 5_000L, FAILED, WalletOrderRefundFailureReason.REFUND_TOTAL_EXCEEDED.name(), NOW);
            return walletOrderRefundRepository.insertIfAbsent(4L, 20L, 100L, 1_000L, COMPLETED, null, NOW);
        });

        // when
        long sut = inTransaction(() -> walletOrderRefundRepository.sumCompletedAmountForShareByOrderId(10L));

        // then
        assertThat(sut).isEqualTo(35_000L);
    }

    @Test
    void 돌려준_기록이_없는_주문의_반환_합계는_0이다() {
        // when
        long sut = inTransaction(() -> walletOrderRefundRepository.sumCompletedAmountForShareByOrderId(10L));

        // then
        assertThat(sut).isZero();
    }

    // 넣기(@Modifying)와 잠금 조회는 트랜잭션 안에서만 돈다. 서비스가 트랜잭션 안에서 부르는 것과 같게 한다
    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
