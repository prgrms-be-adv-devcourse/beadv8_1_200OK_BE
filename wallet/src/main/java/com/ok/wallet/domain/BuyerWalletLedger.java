package com.ok.wallet.domain;

import com.ok.wallet.WalletSchema;
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
 * 구매자 지갑의 잔액 변경 기록. 추가만 하고 수정·삭제하지 않는다.
 * 원장은 BuyerWallet의 잔액 변경 메서드에서만 만들어진다.
 */
@Entity
@Immutable // 엔티티의 변경을 DB에 반영 X
@Table(name = "buyer_wallet_ledger", catalog = WalletSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuyerWalletLedger extends BaseTime {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "buyer_wallet_id", nullable = false)
    private Long buyerWalletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private BuyerWalletLedgerType type;

    @Column(name = "amount", nullable = false)
    private long amount; // 이 변경으로 움직인 금액

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter; // 이 변경이 끝난 직후의 지갑 잔액

    @Column(name = "reference_key", nullable = false)
    private String referenceKey; // 이 변경을 일으킨 원인의 식별자

    private BuyerWalletLedger(Long buyerWalletId, BuyerWalletLedgerType type,
                              long amount, long balanceAfter, String referenceKey) {
        this.buyerWalletId = buyerWalletId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.referenceKey = referenceKey;
    }

    // 같은 패키지의 BuyerWallet에서만 호출한다. 값 검증은 잔액을 바꾸는 BuyerWallet 메서드가 맡는다.
    static BuyerWalletLedger create(Long buyerWalletId, BuyerWalletLedgerType type,
                                    long amount, long balanceAfter, String referenceKey) {
        return new BuyerWalletLedger(buyerWalletId, type, amount, balanceAfter, referenceKey);
    }
}
