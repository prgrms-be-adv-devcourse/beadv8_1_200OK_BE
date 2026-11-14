package com.ok.payout.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.payout.PayoutSchema;
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

// 정산 실패 해결 기록 1건 = 관리자가 실패 기록 1건을 해결한 기록 (#568)
// 요청은 주문 단위지만, 저장은 그 시점에 해결되지 않은 실패 기록마다 1건 (실패 기록 ID 유니크)
//   → 해결 뒤 같은 주문이 다시 실패하면 새 실패 기록에는 해결 기록이 없어 다시 잠기거나 횟수를 처음부터 셈
// 해결 시각은 createdAt(BaseTime)
@Entity
@Immutable
@Table(name = "payout_failure_resolution", catalog = PayoutSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutFailureResolution extends BaseTime {

    // 메모 최대 길이 (DB memo VARCHAR(255)와 맞춤)
    public static final int MAX_MEMO_LENGTH = 255;

    // 해결 요청 번호 길이 (UUID 문자열 36자, DB resolution_request_id VARCHAR(36)과 맞춤)
    private static final int REQUEST_ID_LENGTH = 36;

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    // 해결 요청 번호 (#571): 한 번의 해결 요청(주문 하나)으로 생긴 줄은 모두 같은 값 → 해결 요청 단위로 묶어 볼 때 씀
    //   created_at은 줄마다 따로 찍혀 같은 요청이어도 다를 수 있어 묶는 데 쓸 수 없음
    @Column(nullable = false, length = REQUEST_ID_LENGTH)
    private String resolutionRequestId;

    // 실패 기록은 ID 숫자만 둠 (PayoutFailure가 스냅샷·후보를 숫자로 두는 것과 같음). 없는 ID는 DB 외래키가 막음
    @Column(nullable = false)
    private Long failureId;

    // 주문·주문상품 ID는 실패 기록에서 복사: 잠금 조건·해결된 주문 다시 넣기·해결 기록 조회(#571)를 실패 기록 조인 없이 주문 단위로 하려고
    @Column(nullable = false)
    private Long orderId;

    // 배송비 실패는 null (PayoutFailure.orderItemId와 같음)
    private Long orderItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PayoutResolutionMethod method;

    // 해결한 관리자 회원 ID (member 모듈의 ID라 숫자만 둠)
    @Column(nullable = false)
    private Long adminId;

    @Column(nullable = false, length = MAX_MEMO_LENGTH)
    private String memo;

    // 메모는 사용자 입력이라 RestApiException(422). 요청 DTO가 먼저 막고 여기는 저장 전에 한 번 더 막음 (조정 사유와 같음, api.md "검증 위치")
    // 실패 기록 ID·해결 요청 번호·해결 방법·관리자 ID는 서버가 채우는 값(조회한 실패 기록, 서비스가 요청마다 만든 UUID, 상수, 로그인 토큰)이라
    // 표준 예외 (PayoutFailure.of의 상세와 같음)
    public static PayoutFailureResolution of(PayoutFailure failure, String resolutionRequestId, PayoutResolutionMethod method,
                                             Long adminId, String memo) {
        if (failure.getId() == null || resolutionRequestId == null || method == null || adminId == null) {
            throw new IllegalArgumentException("해결 기록에는 실패 기록 ID, 해결 요청 번호, 해결 방법, 관리자 ID가 필요합니다.");
        }
        if (memo == null || memo.isBlank()) {
            throw new RestApiException(PayoutErrorCode.MISSING_RESOLUTION_MEMO);
        }
        if (memo.length() > MAX_MEMO_LENGTH) {
            throw new RestApiException(PayoutErrorCode.RESOLUTION_MEMO_TOO_LONG);
        }
        PayoutFailureResolution resolution = new PayoutFailureResolution();
        resolution.resolutionRequestId = resolutionRequestId;
        resolution.failureId = failure.getId();
        resolution.orderId = failure.getOrderId();
        resolution.orderItemId = failure.getOrderItemId();
        resolution.method = method;
        resolution.adminId = adminId;
        resolution.memo = memo;
        return resolution;
    }
}
