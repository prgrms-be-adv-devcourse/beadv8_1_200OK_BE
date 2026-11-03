package com.ok.refund.service.dto;

import com.ok.refund.domain.Refund;

import java.time.LocalDateTime;

/**
 * 환불 요청 한 건의 결과. 요청 API 응답과 목록 한 줄이 같은 값을 쓴다.
 * 컨트롤러가 도메인 enum을 쓰지 않도록 상태는 이름 문자열로 담는다.
 * 지금은 요청 단계(#408)의 칸만 있다. 배송비·거절 사유·처리 시각 등은 그 칸을 만드는 이슈에서 더한다.
 *
 * @param itemAmount  돌려줄 상품 금액 = 단가 x 개수 (원)
 * @param requestedAt 요청한 시각 (행을 만든 시각)
 */
public record RefundResult(
        Long refundId,
        Long orderId,
        Long orderItemId,
        int quantity,
        Long buyerId,
        Long sellerId,
        String productName,
        String optionName,
        long unitPrice,
        long itemAmount,
        String cancelReason,
        String status,
        LocalDateTime requestedAt
) {

    public static RefundResult from(Refund refund) {
        return new RefundResult(
                refund.getId(),
                refund.getOrderId(),
                refund.getOrderItemId(),
                refund.getQuantity(),
                refund.getBuyerId(),
                refund.getSellerId(),
                refund.getProductName(),
                refund.getOptionName(),
                refund.getUnitPrice(),
                refund.getItemAmount(),
                refund.getCancelReason(),
                refund.getStatus().name(),
                refund.getCreatedAt());
    }
}
