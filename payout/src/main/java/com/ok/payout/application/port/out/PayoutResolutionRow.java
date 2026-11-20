package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutResolutionMethod;

import java.time.LocalDateTime;

// 해결 기록 한 줄 + 그 해결 기록이 푼 실패 기록과 정산후보 정보 (해결 기록 조회 #571)
// 조회 포트(PayoutQueryRepository)가 돌려주는 결과 모양이라 포트와 같은 application/port/out에 둠 (PayoutItemRow와 같은 이유, #469)
public record PayoutResolutionRow(
        Long resolutionId,
        String resolutionRequestId,
        Long failureId,
        Long orderId,
        Long orderItemId,                  // 배송비 실패는 null
        Long sellerId,                     // 실패 기록의 판매자
        PayoutEventType eventType,         // 실패한 정산후보의 종류 (대금·수수료·배송비)
        Long amount,                       // 실패한 정산후보의 금액
        PayoutFailureReason reason,
        String detail,
        LocalDateTime failedAt,            // 실패 기록 생성 시각
        PayoutResolutionMethod method,
        Long adminId,
        LocalDateTime resolvedAt,          // 해결 기록 생성 시각
        String memo
) {
}
