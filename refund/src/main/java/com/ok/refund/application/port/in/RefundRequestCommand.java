package com.ok.refund.application.port.in;

/**
 * 구매자의 환불 요청 입력.
 *
 * @param buyerId      토큰에서 꺼낸 요청한 구매자 ID
 * @param quantity     취소할 개수
 * @param cancelReason 구매자가 적은 취소 사유. 안 적었으면 {@code null}
 */
public record RefundRequestCommand(
        Long buyerId,
        Long orderId,
        Long orderItemId,
        int quantity,
        String cancelReason
) {
}
