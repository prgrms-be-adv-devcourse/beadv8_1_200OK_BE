package com.ok.payout.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import static jakarta.persistence.GenerationType.IDENTITY;

@Entity
@Immutable
@Table(name = "payout_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutItem extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payout_id", nullable = false)
    private Payout payout;

    // 정산 대상 정산후보 ID (정산후보 1건 = 정산 항목 1건, UK)
    // 연관관계(@ManyToOne) 대신 ID 값만 보관: 확정 시 draft 항목의 값을 복사해 두고 후보를 따라가 조회할 일이 없음
    // (정합성은 DB FK fk_payout_item_candidate_id로 보장)
    @Column(nullable = false, unique = true)
    private Long candidateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PayoutEventType eventType;

    @Column(nullable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private Long amount;

    // draft 항목 1건을 정산 항목으로 옮긴다. Payout.addItem()에서만 호출 (public 아님)
    // 값은 draft 항목에서 복사 (draft 항목은 저장된 정산후보에서 만들어져 candidateId가 항상 있음)
    PayoutItem(Payout payout, Long candidateId, PayoutEventType eventType, Long orderItemId, Long sellerId, Long amount) {
        this.payout = payout;
        this.candidateId = candidateId;
        this.eventType = eventType;
        this.orderItemId = orderItemId;
        this.sellerId = sellerId;
        this.amount = amount;
    }
}
