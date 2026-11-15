package com.ok.payout.application.port.in;

import java.time.LocalDateTime;

// 주문 해결 결과 (#568): 방금 저장한 해결 기록을 주문 단위로 묶어 돌려줌
// 해결 기록은 실패 기록마다 1건이지만 한 요청에서 같은 관리자·시각·메모로 저장되므로, 건수만 따로 둠
public record PayoutOrderResolutionResult(
        Long orderId,
        // 해결 요청 번호 (#571): 해결 기록 조회의 묶음 보기에서 이 요청을 찾을 때 씀
        String resolutionRequestId,
        // 이번에 해결한 실패 기록 수
        int resolvedFailureCount,
        // 해결 방법 이름 (REPROCESS). 입력 포트는 domain enum을 내보내지 않으려고 문자열로 둠
        String method,
        Long adminId,
        LocalDateTime resolvedAt,
        String memo
) {
}
