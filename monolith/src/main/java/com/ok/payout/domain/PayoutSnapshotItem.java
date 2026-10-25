package com.ok.payout.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.payout.exception.PayoutErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import static jakarta.persistence.GenerationType.IDENTITY;

// 스냅샷 대상 목록 1건 = 정산후보 1건. 정산후보를 복사하지 않고 ID와 금액만 기록 (verifyStep의 비교 기준)
@Entity
@Immutable
@Table(name = "payout_snapshot_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutSnapshotItem extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    // 객체(@ManyToOne) 대신 ID 숫자만 둠: 스냅샷·정산후보의 다른 정보를 따라가 꺼낼 일이 없음
    // 없는 스냅샷·정산후보 ID가 들어가지 않는 것은 DB 외래키(fk_payout_snapshot_item_snapshot_id, fk_payout_snapshot_item_candidate_id)가 막아 줌
    @Column(nullable = false)
    private Long snapshotId;

    @Column(nullable = false)
    private Long candidateId;

    // 스냅샷 시점의 정산후보 금액 (원)
    @Column(nullable = false)
    private Long amount;

    public static PayoutSnapshotItem create(Long snapshotId, PayoutCandidateItem candidate) {
        // 저장 전(id 없는) 후보면 flush 시점이 아니라 여기서 바로 실패
        if (candidate.getId() == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_CANDIDATE_ID);
        }
        PayoutSnapshotItem item = new PayoutSnapshotItem();
        item.snapshotId = snapshotId;
        item.candidateId = candidate.getId();
        item.amount = candidate.getAmount();
        return item;
    }
}
