package com.ok.order.order.application.dto;

// 환불이 승인 뒤 끝내 실패해 보낸 취소 기록 되돌리기 요청(#604)
public record RevertOrderItemRefundCommand(
        Long refundId,
        Long orderId,
        Long orderItemId,
        int quantity   // 되돌릴 취소 수량. 남긴 기록의 수량과 같아야 되돌린다
) {
}
