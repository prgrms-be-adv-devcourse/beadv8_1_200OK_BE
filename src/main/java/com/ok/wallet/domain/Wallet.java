package com.ok.wallet.domain;

import com.ok.common.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "wallet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet extends BaseIdAndTime {
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private WalletType type;

    @Column(name = "balance", nullable = false)
    private Long balance;

    private Wallet(Long memberId, WalletType type) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId는 필수입니다.");
        }
        if (type == null) {
            throw new IllegalArgumentException("type은 필수입니다.");
        }
        this.memberId = memberId;
        this.type = type;
        this.balance = 0L; // 처음 지갑 생성 시 0원
    }

    public static Wallet create(Long memberId, WalletType type) {
        return new Wallet(memberId, type);
    }
}