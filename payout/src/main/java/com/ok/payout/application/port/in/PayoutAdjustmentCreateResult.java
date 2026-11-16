package com.ok.payout.application.port.in;

import java.time.LocalDateTime;

// 조정 생성 결과 (#291): 방금 저장한 조정 정산후보
// 주문·판매자는 요청이 아니라 원래 후보에서 복사한 값이라, 관리자가 어느 판매자 몫에 들어갔는지 바로 확인할 수 있게 돌려줌
public record PayoutAdjustmentCreateResult(
        // 생성된 조정 정산후보 ID
        Long candidateId,
        // 서버가 붙인 조정 번호 (같은 주문상품·종류의 마지막 번호 + 1)
        Long adjustmentId,
        Long orderId,
        Long orderItemId,
        Long sellerId,
        // 정산 항목 종류 이름 (SALE_AMOUNT, SALE_FEE). 입력 포트는 domain enum을 내보내지 않으려고 문자열로 둠
        String eventType,
        Long amount,
        String reason,
        Long adminId,
        LocalDateTime createdAt
) {
}
