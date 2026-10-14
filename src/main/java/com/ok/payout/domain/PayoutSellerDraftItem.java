package com.ok.payout.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import static jakarta.persistence.GenerationType.IDENTITY;

// 판매자 몫 임시 정산 항목 (판매 대금, 배송비). 정산후보 1건 = 항목 1건
@Entity
@Immutable
@Table(name = "payout_seller_draft_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutSellerDraftItem extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    // 부모(PayoutSellerDraft)와 같은 값. "스냅샷당 정산후보 1건" UK(snapshot_id, candidate_id)를 걸기 위해 항목에도 둠
    @Column(nullable = false)
    private Long snapshotId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_draft_id", nullable = false)
    private PayoutSellerDraft sellerDraft;

    @Column(nullable = false)
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

    // 정산후보 1건을 판매자 몫 항목으로 옮긴다. PayoutSellerDraft.create()에서만 호출 (public 아님)
    PayoutSellerDraftItem(PayoutSellerDraft sellerDraft, PayoutCandidateItem candidate) {
        // 저장 전(id 없는) 후보면 flush 시점이 아니라 여기서 바로 실패
        if (candidate.getId() == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_CANDIDATE_ID);
        }
        // 정산후보 하나는 판매자 몫·시스템 몫 중 한 곳에만 들어가야 함. 수수료가 섞이면 호출 쪽 분류 버그
        if (candidate.isSystemShare()) {
            throw new IllegalArgumentException("시스템 몫 정산후보는 판매자 임시 정산에 넣을 수 없습니다. candidateId=" + candidate.getId());
        }
        this.snapshotId = sellerDraft.getSnapshotId();
        this.sellerDraft = sellerDraft;
        this.candidateId = candidate.getId();
        this.eventType = candidate.getEventType();
        this.orderItemId = candidate.getOrderItemId();
        this.sellerId = candidate.getSellerId();
        this.amount = candidate.getAmount();
    }
}
