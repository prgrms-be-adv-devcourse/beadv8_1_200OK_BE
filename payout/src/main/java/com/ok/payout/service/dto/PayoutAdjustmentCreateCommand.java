package com.ok.payout.service.dto;

// eventType은 문자열로 받음. 컨트롤러 쪽은 domain enum을 쓸 수 없어서, enum 변환은 조정 기능을 열 때 서비스에서 함
public record PayoutAdjustmentCreateCommand(
        // 조정할 주문 항목 ID
        Long orderItemId,
        // 조정할 정산 항목 종류 (SALE_AMOUNT, SALE_SHIPPING_FEE, SALE_FEE). 원래 정산후보와 같은 종류
        String eventType,
        // 조정 금액 (원). 음수면 차감, 0은 요청 DTO에서 거절됨
        Long amount,
        // 조정 사유 (앞뒤 공백 제거됨, 255자 이하)
        String reason
) {
}
