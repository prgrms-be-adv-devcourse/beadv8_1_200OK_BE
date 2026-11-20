package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutResolutionMethod;

import java.time.LocalDateTime;

// 해결 요청 1번 = 1줄 (해결 기록 조회 #571, 묶음 보기). 해결 기록을 요청 번호로 묶은 결과
// 같은 요청의 줄은 주문·방법·관리자·메모가 같고, 해결 시각만 줄마다 따로 찍혀 가장 이른 값을 씀
public record PayoutResolutionRequestRow(
        String resolutionRequestId,
        Long orderId,
        PayoutResolutionMethod method,
        Long adminId,
        LocalDateTime resolvedAt,
        String memo,
        Long resolvedFailureCount          // 이 요청으로 해결한 실패 기록 수 (Querydsl count가 Long이라 Long)
) {
}
