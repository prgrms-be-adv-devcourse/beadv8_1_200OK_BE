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
@Table(name = "seller_wallet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerWallet extends BaseTime {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "balance", nullable = false)
    private long balance;

    private SellerWallet(Long memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId는 필수입니다.");
        }
        this.memberId = memberId;
        this.balance = 0L; // 판매자 지갑 생성 시 0원
    }

    public static SellerWallet create(Long memberId) {
        return new SellerWallet(memberId);
    }

    /**
     * 정산금을 입금해 잔액을 늘리고 정산 입금 원장을 반환한다.
     * referenceKey는 "PAYOUT:{payoutId}" 형식이다.
     * 같은 정산은 모든 판매자 지갑을 통틀어 한 번만 입금되며, 이미 원장에 있는 키로 다시 저장하면 유니크 제약으로 실패한다.
     */
    public SellerWalletLedger deposit(long amount, String referenceKey) {
        validate(amount, referenceKey);
        // 금액은 정산 금액 범위 안에서 들어오므로 넘칠 일이 없다.
        // 넘친다면 잘못된 값이 들어온 것이므로 잔액이 음수로 바뀌지 않도록 예외로 멈춘다.
        this.balance = Math.addExact(this.balance, amount);
        return SellerWalletLedger.create(this.id, SellerWalletLedgerType.PAYOUT, amount, this.balance, referenceKey);
    }

    // 금액은 0보다 커야 하는 규칙이라 규칙 위반으로 응답하고,
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
