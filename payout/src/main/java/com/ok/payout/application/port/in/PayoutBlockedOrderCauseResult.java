package com.ok.payout.application.port.in;

import java.time.LocalDateTime;

// 막힌 주문의 원인 1건 (#569). 함께 보류는 이미 빠져 있고 같은 기록은 1번만 (#582)
// enum 값은 컨트롤러가 domain enum을 쓸 수 없어 이름 문자열로 넘김
public record PayoutBlockedOrderCauseResult(
        // 배송비 후보는 null
        Long orderItemId,
        // 실패한 정산후보 종류 (SALE_AMOUNT, SALE_FEE, SALE_SHIPPING_FEE)
        String eventType,
        // 실패 사유 이름 (PayoutFailureReason)
        String reason,
        // 원인 범주 이름 (PayoutFailureCategory). 사유에서 꺼냄
        String category,
        String detail,
        Long sellerId,
        // 같은 원인 기록 중 가장 늦은 실패 시각
        LocalDateTime failedAt
) {
}
