package com.ok.payout.domain;

import com.ok.common.exception.RestApiException;
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
    // 연관관계(@ManyToOne) 대신 ID 값만 보관: 정산 시 후보 값을 복사해 두고 후보를 따라가 조회할 일이 없음
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

    // 정산 후보 1건을 정산 항목으로 옮긴다. Payout.create()에서만 호출 (public 아님)
    PayoutItem(Payout payout, PayoutCandidateItem candidate) {
        // 저장 전(id 없는) 후보면 flush 시점이 아니라 여기서 바로 실패
        if (candidate.getId() == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_CANDIDATE_ID);
        }
        this.payout = payout;
        this.candidateId = candidate.getId();
        this.eventType = candidate.getEventType();
        this.orderItemId = candidate.getOrderItemId();
        this.sellerId = candidate.getSellerId();
        this.amount = candidate.getAmount();
    }
}
