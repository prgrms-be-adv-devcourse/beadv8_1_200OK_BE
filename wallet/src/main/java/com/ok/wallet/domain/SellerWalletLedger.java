package com.ok.wallet.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 판매자 지갑의 잔액 변경 기록. 추가만 하고 수정·삭제하지 않는다.
 * 원장은 SellerWallet의 잔액 변경 메서드에서만 만들어진다.
 */
@Entity
@Immutable // 변경해도 UPDATE가 발생하지 않음
@Table(name = "seller_wallet_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerWalletLedger extends BaseTime {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_wallet_id", nullable = false)
    private Long sellerWalletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private SellerWalletLedgerType type;

    @Column(name = "amount", nullable = false)
    private long amount; // 이번에 움직인 금액

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter; // 이번 변경 직후의 판매자 지갑 잔액

    @Column(name = "reference_key", nullable = false)
    private String referenceKey; // 잔액을 바꾼 원인

    private SellerWalletLedger(Long sellerWalletId, SellerWalletLedgerType type,
                               long amount, long balanceAfter, String referenceKey) {
        this.sellerWalletId = sellerWalletId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.referenceKey = referenceKey;
    }

    // 원장은 SellerWallet의 잔액 변경 메서드(deposit)에서만 만든다.
    // 금액 확인 같은 검증은 지갑이 이미 했으므로 여기서는 값만 담는다.
    static SellerWalletLedger create(Long sellerWalletId, SellerWalletLedgerType type,
                                     long amount, long balanceAfter, String referenceKey) {
        return new SellerWalletLedger(sellerWalletId, type, amount, balanceAfter, referenceKey);
    }
}
