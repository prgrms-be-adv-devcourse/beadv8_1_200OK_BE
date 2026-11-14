package com.ok.payout.application.port.in;

import java.time.LocalDateTime;

// 해결 기록 1건 (#571, 1줄씩 보기): 해결 기록 + 그 해결 기록이 푼 실패 기록·정산후보 정보
// enum 값은 컨트롤러가 domain enum을 쓸 수 없어 이름 문자열로 넘김
public record PayoutResolutionResult(
        Long resolutionId,
        // 해결 요청 번호: 같은 해결 요청에서 생긴 줄은 모두 같음
        String resolutionRequestId,
        Long failureId,
        Long orderId,
        // 배송비 실패는 null
        Long orderItemId,
        // 실패 기록의 판매자 회원 ID
        Long sellerId,
        // 실패한 정산후보의 종류 (SALE_AMOUNT, SALE_FEE, SALE_SHIPPING_FEE)
        String eventType,
        // 실패한 정산후보의 금액 (원)
        Long amount,
        // 실패 사유 이름 (PayoutFailureReason)
        String reason,
        // 실패 사유의 원인 범주 이름 (PayoutFailureCategory)
        String category,
        String detail,
        LocalDateTime failedAt,
        // 해결 방법 이름 (REPROCESS)
        String method,
        Long adminId,
        LocalDateTime resolvedAt,
        String memo
) {
}
