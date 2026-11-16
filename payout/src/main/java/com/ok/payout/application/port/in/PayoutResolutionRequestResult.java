package com.ok.payout.application.port.in;

import java.time.LocalDateTime;

// 해결 요청 1번 (#571, 묶음 보기): 같은 요청 번호의 해결 기록을 한 줄로 묶은 것
public record PayoutResolutionRequestResult(
        String resolutionRequestId,
        Long orderId,
        // 해결 방법 이름 (REPROCESS). 입력 포트는 domain enum을 내보내지 않으려고 문자열로 둠
        String method,
        Long adminId,
        // 그 요청에서 가장 이른 해결 시각 (줄마다 따로 찍혀 아주 조금씩 다를 수 있음)
        LocalDateTime resolvedAt,
        String memo,
        // 이 요청으로 해결한 실패 기록 수 (#568 해결 응답의 resolvedFailureCount와 같은 뜻)
        int resolvedFailureCount
) {
}
