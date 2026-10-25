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

// 정산 실패 기록 1건 = 이번 스냅샷에서 정산하지 않고 뺀 정산후보 1건과 그 사유
// 주문·판매자 정보를 후보에서 복사해 둠: 주문 잠금(#195)·판매자별 확인을 정산후보 조인 없이 조회하려고
@Entity
@Immutable
@Table(name = "payout_failure")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutFailure extends BaseTime {

    // 상세 최대 길이 (DB detail VARCHAR(255)와 맞춤)
    public static final int MAX_DETAIL_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    // 스냅샷·정산후보는 ID 숫자만 둠 (PayoutSnapshotItem과 같은 이유). 없는 ID는 DB 외래키가 막음
    @Column(nullable = false)
    private Long snapshotId;

    @Column(nullable = false)
    private Long candidateId;

    @Column(nullable = false)
    private Long orderId;

    // 배송비 후보는 null (PayoutCandidateItem.orderItemId와 같음)
    private Long orderItemId;

    @Column(nullable = false)
    private Long sellerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PayoutFailureReason reason;

    @Column(nullable = false, length = MAX_DETAIL_LENGTH)
    private String detail;

    // 상세에 남길 값이 없는 실패: 사유의 기본 문구를 상세로 씀
    public static PayoutFailure of(Long snapshotId, PayoutCandidateItem candidate, PayoutFailureReason reason) {
        return of(snapshotId, candidate, reason, reason.getDefaultDetail());
    }

    // 상세(검사 종류, 기대값·실제값 등)가 있는 실패 (#195 금액 검사에서 사용)
    public static PayoutFailure of(Long snapshotId, PayoutCandidateItem candidate, PayoutFailureReason reason,
                                   String detail) {
        // 저장 전(id 없는) 후보면 flush 시점이 아니라 여기서 바로 실패 (PayoutSnapshotItem.create와 같음)
        if (candidate.getId() == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_CANDIDATE_ID);
        }
        // 정산 배치가 만드는 값이라 사용자 입력이 아님 → 표준 예외 (api.md "검증 위치")
        if (detail == null || detail.isBlank() || detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("실패 상세는 1~" + MAX_DETAIL_LENGTH + "자여야 합니다. detail=" + detail);
        }
        PayoutFailure failure = new PayoutFailure();
        failure.snapshotId = snapshotId;
        failure.candidateId = candidate.getId();
        failure.orderId = candidate.getOrderId();
        failure.orderItemId = candidate.getOrderItemId();
        failure.sellerId = candidate.getSellerId();
        failure.reason = reason;
        failure.detail = detail;
        return failure;
    }
}
