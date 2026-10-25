package com.ok.payout.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.payout.exception.PayoutErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import static jakarta.persistence.GenerationType.IDENTITY;

// 시스템 몫 임시 정산 항목 (판매 수수료). 정산후보 1건 = 항목 1건
// 합계(부모)가 없음: 여러 판매자·여러 회차의 수수료를 합쳐야 해서, commitStep에서 스냅샷 전체를 합쳐 payout 1건으로 만듦
@Entity
@Immutable
@Table(name = "payout_system_draft_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutSystemDraftItem extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long snapshotId;

    @Column(nullable = false)
    private Long candidateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PayoutEventType eventType;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long orderItemId;

    // 수수료가 발생한 원래 판매자 (돈을 받는 사람이 아님)
    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private Long amount;

    public static PayoutSystemDraftItem create(Long snapshotId, PayoutCandidateItem candidate) {
        if (candidate.getId() == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_CANDIDATE_ID);
        }
        // 정산후보 하나는 판매자 몫·시스템 몫 중 한 곳에만 들어가야 함. 판매 대금·배송비가 섞이면 호출 쪽 분류 버그
        if (!candidate.isSystemShare()) {
            throw new IllegalArgumentException("판매자 몫 정산후보는 시스템 임시 정산에 넣을 수 없습니다. candidateId=" + candidate.getId());
        }
        PayoutSystemDraftItem item = new PayoutSystemDraftItem();
        item.snapshotId = snapshotId;
        item.candidateId = candidate.getId();
        item.eventType = candidate.getEventType();
        item.orderId = candidate.getOrderId();
        item.orderItemId = candidate.getOrderItemId();
        item.sellerId = candidate.getSellerId();
        item.amount = candidate.getAmount();
        return item;
    }
}
