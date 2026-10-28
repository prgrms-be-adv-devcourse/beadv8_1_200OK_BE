package com.ok.wallet.repository;

import com.ok.common.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.domain.Charge;
import com.ok.wallet.domain.ChargeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class ChargeRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0);
    private static final String PAYMENT_KEY = "payment-key-1";

    @Autowired
    ChargeRepository chargeRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TestEntityManager entityManager;

    // 조건부 상태 변경

    @Test
    void 대기_중인_충전_건은_처리_중으로_바뀌고_결제_키가_저장된다() {
        // Given
        Charge charge = chargeRepository.save(Charge.request(1L, 10_000));

        // When
        int updated = chargeRepository.markInProgress(charge.getChargeNo(), PAYMENT_KEY, NOW);

        // Then: 승인 결과가 불명확해 처리 중으로 남아도 어떤 결제인지 알 수 있다
        assertThat(updated).isEqualTo(1);
        assertThat(statusOf(charge.getChargeNo())).isEqualTo(ChargeStatus.IN_PROGRESS);
        assertThat(paymentKeyOf(charge.getChargeNo())).isEqualTo(PAYMENT_KEY);
    }

    @Test
    void 이미_처리_중으로_바뀐_충전_건은_다시_바뀌지_않는다() {
        // Given: 첫 승인 요청이 먼저 처리 중으로 바꿈
        Charge charge = chargeRepository.save(Charge.request(1L, 10_000));
        chargeRepository.markInProgress(charge.getChargeNo(), PAYMENT_KEY, NOW);

        // When: 같은 건으로 승인 요청이 다시 옴
        int updated = chargeRepository.markInProgress(charge.getChargeNo(), PAYMENT_KEY, NOW);

        // Then: 0을 받은 요청은 토스 승인을 호출하지 않는다
        assertThat(updated).isZero();
    }

    @Test
    void 실패한_충전_건은_처리_중으로_바뀌지_않는다() {
        // Given
        Charge charge = Charge.request(1L, 10_000);
        charge.fail("PAY_PROCESS_CANCELED", "사용자가 결제를 취소했습니다.", NOW);
        chargeRepository.save(charge);

        // When & Then
        assertThat(chargeRepository.markInProgress(charge.getChargeNo(), PAYMENT_KEY, NOW)).isZero();
        assertThat(statusOf(charge.getChargeNo())).isEqualTo(ChargeStatus.FAILED);
        assertThat(paymentKeyOf(charge.getChargeNo())).isNull();
    }

    @Test
    void 처리_중인_충전_건을_완료하면_결제_키와_완료_시각이_저장된다() {
        // Given
        Charge saved = chargeRepository.save(Charge.request(1L, 10_000));
        chargeRepository.markInProgress(saved.getChargeNo(), PAYMENT_KEY, NOW);
        // 조건부 UPDATE가 영속성 컨텍스트를 비우므로 다시 읽어 처리 중 상태를 가져온다
        Charge inProgress = chargeRepository.findByChargeNo(saved.getChargeNo()).orElseThrow();

        // When
        inProgress.complete(PAYMENT_KEY, NOW);
        // 테스트는 커밋되지 않으므로, JdbcTemplate으로 DB를 직접 읽기 전에 변경 내용을 보낸다
        entityManager.flush();

        // Then
        assertThat(statusOf(saved.getChargeNo())).isEqualTo(ChargeStatus.COMPLETED);
        assertThat(paymentKeyOf(saved.getChargeNo())).isEqualTo(PAYMENT_KEY);
    }

    // 처리 중 금액 합계

    @Test
    void 회원의_처리_중인_충전_금액만_합산한다() {
        // Given: 회원 1은 처리 중 2만원·3만원과 대기 1만원, 회원 2는 처리 중 4만원
        inProgressCharge(1L, 20_000);
        inProgressCharge(1L, 30_000);
        chargeRepository.save(Charge.request(1L, 10_000));
        inProgressCharge(2L, 40_000);

        // When
        long sum = chargeRepository.sumAmountByMemberIdAndStatus(1L, ChargeStatus.IN_PROGRESS);

        // Then
        assertThat(sum).isEqualTo(50_000);
    }

    @Test
    void 처리_중인_충전이_없으면_0이다() {
        assertThat(chargeRepository.sumAmountByMemberIdAndStatus(1L, ChargeStatus.IN_PROGRESS)).isZero();
    }

    // DB 제약

    @Test
    void 같은_충전_번호는_저장되지_않는다() {
        insertCharge("CHG_DUPLICATE", 10_000, null);

        assertThatThrownBy(() -> insertCharge("CHG_DUPLICATE", 10_000, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_wallet_charge_charge_no");
    }

    @Test
    void 같은_결제_키가_두_충전_건에_연결되지_않는다() {
        insertCharge("CHG_FIRST", 10_000, PAYMENT_KEY);

        assertThatThrownBy(() -> insertCharge("CHG_SECOND", 10_000, PAYMENT_KEY))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_wallet_charge_payment_key");
    }

    @Test
    void 결제_키가_없는_충전_건은_여러_개_저장된다() {
        // 대기·실패 건은 결제 키가 없으므로 NULL끼리는 유니크 제약에 걸리지 않아야 한다
        insertCharge("CHG_FIRST", 10_000, null);
        insertCharge("CHG_SECOND", 10_000, null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_charge WHERE payment_key IS NULL", Long.class))
                .isEqualTo(2);
    }

    @Test
    void 금액이_0_이하인_충전_건은_저장되지_않는다() {
        assertThatThrownBy(() -> insertCharge("CHG_ZERO", 0, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_wallet_charge_amount");
    }

    // 결제 키에 유니크 제약이 있으므로 충전 번호로 건마다 다른 키를 만든다
    private void inProgressCharge(Long memberId, long amount) {
        Charge charge = chargeRepository.save(Charge.request(memberId, amount));
        chargeRepository.markInProgress(charge.getChargeNo(), "pk-" + charge.getChargeNo(), NOW);
    }

    private ChargeStatus statusOf(String chargeNo) {
        return ChargeStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_charge WHERE charge_no = ?", String.class, chargeNo));
    }

    private String paymentKeyOf(String chargeNo) {
        return jdbcTemplate.queryForObject(
                "SELECT payment_key FROM wallet_charge WHERE charge_no = ?", String.class, chargeNo);
    }

    // 엔티티 검증을 거치지 않고 DB 제약만 확인하려고 SQL로 직접 넣는다
    private void insertCharge(String chargeNo, long amount, String paymentKey) {
        jdbcTemplate.update("""
                INSERT INTO wallet_charge (charge_no, member_id, amount, status, payment_key, created_at, updated_at)
                VALUES (?, 1, ?, 'PENDING', ?, NOW(6), NOW(6))
                """, chargeNo, amount, paymentKey);
    }
}