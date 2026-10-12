package com.ok.wallet.domain;

import com.ok.common.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "buyer_wallet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuyerWallet extends BaseIdAndTime {
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
}
