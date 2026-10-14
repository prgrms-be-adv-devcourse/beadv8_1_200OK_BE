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

    /** 잔액을 늘리고 충전 원장을 반환한다. */
    public BuyerWalletLedger charge(long amount, String referenceKey) {
        return increase(BuyerWalletLedgerType.CHARGE, amount, referenceKey);
    }

    /** 잔액을 줄이고 결제 원장을 반환한다. 잔액이 부족하면 변경하지 않고 예외를 던진다. */
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
     */
    public BuyerWalletLedger refund(long amount, String referenceKey) {
        return increase(BuyerWalletLedgerType.REFUND, amount, referenceKey);
    }

    private BuyerWalletLedger increase(BuyerWalletLedgerType type, long amount, String referenceKey) {
        validate(amount, referenceKey);
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
}
