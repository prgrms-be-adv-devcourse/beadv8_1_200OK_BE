package com.ok.wallet.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.wallet.exception.WalletErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "buyer_wallet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuyerWallet extends BaseTime {
    // 무기명식 선불전자지급수단 발행권면 최고한도 (전자금융거래법 시행령 제13조).
    // 본인인증을 하지 않으므로 무기명식 기준을 적용한다.
    public static final long MAX_BALANCE = 500_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "balance", nullable = false)
    private long balance;

    private BuyerWallet(Long memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId는 필수입니다.");
        }
        this.memberId = memberId;
        this.balance = 0L; // 처음 지갑 생성 시 0원
    }

    public static BuyerWallet create(Long memberId) {
        return new BuyerWallet(memberId);
    }

    /**
     * 잔액을 늘리고 충전 원장을 반환한다.
     * referenceKey는 "CHARGE:{paymentKey}" 형식으로, 지갑 전체에서 한 번만 쓰여야 한다.
     */
    public BuyerWalletLedger charge(long amount, String referenceKey) {
        return increase(BuyerWalletLedgerType.CHARGE, amount, referenceKey);
    }

    /** 이 금액을 결제할 만큼 잔액이 있는지 확인한다. */
    public boolean hasEnoughBalance(long amount) {
        return this.balance >= amount;
    }

    /**
     * 잔액을 줄이고 결제 원장을 반환한다. 잔액이 부족하면 변경하지 않고 예외를 던진다.
     * referenceKey는 "PAYMENT:{주문 ID}" 형식이며, 모든 지갑을 통틀어 한 번만 쓰여야 한다.
     */
    public BuyerWalletLedger pay(long amount, String referenceKey) {
        validate(amount, referenceKey);
        if (this.balance < amount) {
            throw new RestApiException(WalletErrorCode.INSUFFICIENT_BALANCE);
        }
        this.balance -= amount;
        return BuyerWalletLedger.create(this.id, BuyerWalletLedgerType.PAYMENT, amount, this.balance, referenceKey);
    }

    /**
     * 결제했던 금액을 지갑으로 되돌리고 환불 원장을 반환한다. 세미에서는 배송 전 취소 시 호출된다.
     * 돌려줄 금액이 맞는지는 호출하는 쪽에서 확인한다.
     * referenceKey는 "REFUND:{취소 건 ID 또는 주문 상품 ID}" 형식이며, 모든 지갑을 통틀어 한 번만 쓰여야 한다.
     */
    public BuyerWalletLedger refund(long amount, String referenceKey) {
        return increase(BuyerWalletLedgerType.REFUND, amount, referenceKey);
    }

    private BuyerWalletLedger increase(BuyerWalletLedgerType type, long amount, String referenceKey) {
        validate(amount, referenceKey);
        // 금액은 PG 승인 한도 안에서 들어오므로 넘칠 일이 없다.
        // 넘친다면 잘못된 값이 들어온 것이므로 잔액이 음수로 바뀌지 않도록 예외로 멈춘다.
        this.balance = Math.addExact(this.balance, amount);
        return BuyerWalletLedger.create(this.id, type, amount, this.balance, referenceKey);
    }

    // 금액은 사용자 요청에서 오므로 규칙 위반으로 응답하고,
    // 근거 키는 호출하는 코드가 만들어 넘기는 값이라 없으면 코드의 버그로 본다.
    private void validate(long amount, String referenceKey) {
        if (referenceKey == null || referenceKey.isBlank()) {
            throw new IllegalArgumentException("referenceKey는 필수입니다.");
        }
        if (amount <= 0) {
            throw new RestApiException(WalletErrorCode.INVALID_AMOUNT);
        }
    }

    /** 지금 잔액에 금액을 더했을 때 보유 한도를 넘는지 확인한다. */
    public static boolean exceedsMaxBalance(long balance, long amount) {
        // 잔액 + 금액은 아주 큰 금액에서 long 범위를 넘어 음수가 될 수 있어, 남은 한도와 비교한다
        return amount > MAX_BALANCE - balance;
    }
}
