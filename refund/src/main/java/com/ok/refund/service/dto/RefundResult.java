package com.ok.refund.service.dto;

import com.ok.refund.domain.Refund;

import java.time.LocalDateTime;

/**
 * 환불 요청 한 건의 결과. 요청 API 응답과 목록 한 줄이 같은 값을 쓴다.
 * 컨트롤러가 도메인 enum을 쓰지 않도록 상태는 이름 문자열로 담는다.
 * 실패 기록(#412), 완료 시각(#414) 등은 그 칸을 만드는 이슈에서 더한다.
 *
 * @param itemAmount                돌려줄 상품 금액 = 단가 x 개수 (원)
 * @param shippingFeeAmount         돌려줄 배송비 (원). 승인 때 정해지고 그 전에는 0
 * @param remoteAreaFeeAmount       돌려줄 도서산간 운임 (원). 승인 때 정해지고 그 전에는 0
 * @param deductedShippingFeeAmount 환불 금액에서 뺀 배송비 (원). 승인 때 정해지고 그 전에는 0
 * @param refundAmount              돌려줄 금액 = 상품 금액 + 돌려줄 배송비 + 도서산간 운임 − 뺀 배송비 (원)
 * @param rejectReason              판매자가 적은 거절 사유(자동 거절이면 시스템 사유). 거절이 아니면 {@code null}
 * @param requestedAt               요청한 시각 (행을 만든 시각)
 * @param decidedAt                 승인·거절·철회한 시각. 요청됨이면 {@code null}
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
        long shippingFeeAmount,
        long remoteAreaFeeAmount,
        long deductedShippingFeeAmount,
        long refundAmount,
        String cancelReason,
        String status,
        String rejectReason,
        LocalDateTime requestedAt,
        LocalDateTime decidedAt
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
                refund.getShippingFeeAmount(),
                refund.getRemoteAreaFeeAmount(),
                refund.getDeductedShippingFeeAmount(),
                refund.getRefundAmount(),
                refund.getCancelReason(),
                refund.getStatus().name(),
                refund.getRejectReason(),
                refund.getCreatedAt(),
                refund.getDecidedAt());
    }
}
