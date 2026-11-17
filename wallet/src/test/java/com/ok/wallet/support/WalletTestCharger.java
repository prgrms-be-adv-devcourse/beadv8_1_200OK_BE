package com.ok.wallet.support;

import com.ok.wallet.application.service.BuyerWalletService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * 시험 준비 단계에서 구매자 지갑에 예치금을 넣는다.
 * 실제 충전은 완료된 충전 건과 충전 원장이 짝으로 생기고, 정리 단계 검사(sql/wallet-balance-check.sql)는 짝이 없는 충전 원장을 어긋남으로 잡는다.
 * 그래서 충전 함수만 부르지 않고 완료 충전 건도 함께 넣는다. 토스 승인 흐름을 다 타지 않는 건 잔액만 있으면 되는 시험이 토스 가짜 객체에 묶이지 않게 하려는 것이다.
 */
public class WalletTestCharger {

    private final JdbcTemplate jdbcTemplate;
    private final BuyerWalletService buyerWalletService;

    public WalletTestCharger(JdbcTemplate jdbcTemplate, BuyerWalletService buyerWalletService) {
        this.jdbcTemplate = jdbcTemplate;
        this.buyerWalletService = buyerWalletService;
    }

    /**
     * 완료 충전 건을 넣고 그 결제로 충전한다.
     */
    public void charge(Long memberId, String paymentKey, long amount) {
        recordCompletedCharge(memberId, paymentKey, amount);
        buyerWalletService.chargeByPayment(memberId, paymentKey, amount);
    }

    /**
     * 완료 충전 건만 넣는다. 충전 함수 자체를 시험할 때 준비 단계에서 쓰고, 충전 함수는 시험이 직접 부른다.
     */
    public void recordCompletedCharge(Long memberId, String paymentKey, long amount) {
        jdbcTemplate.update("""
                INSERT INTO wallet_charge (charge_no, member_id, amount, status, payment_key, completed_at, created_at, updated_at)
                VALUES (?, ?, ?, 'COMPLETED', ?, NOW(6), NOW(6), NOW(6))
                """, "CHG_" + UUID.randomUUID().toString().replace("-", ""), memberId, amount, paymentKey);
    }
}
