package com.ok.payout.domain;

import com.ok.common.jpa.entity.BaseIdAndTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "payout_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutItem extends BaseIdAndTime {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payout_id", nullable = false)
    private Payout payout;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PayoutEventType eventType;

    @Column(nullable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private Long amount;

    // 정산 후보 1건을 정산 항목으로 옮긴다. Payout.create()에서만 호출 (public 아님)
    PayoutItem(Payout payout, PayoutCandidateItem candidate) {
        this.payout = payout;
        this.eventType = candidate.getEventType();
        this.orderItemId = candidate.getOrderItemId();
        this.sellerId = candidate.getSellerId();
        this.amount = candidate.getAmount();
    }
}
