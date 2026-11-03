package com.ok.refund.service.dto;

import com.ok.refund.domain.Refund;

import java.time.LocalDateTime;

/**
 * 환불 요청 한 건의 조회·처리 결과. 목록 한 줄과 처리 API 응답이 같은 값을 쓴다(상세 API는 두지 않는다).
 * 컨트롤러가 도메인 enum을 쓰지 않도록 상태·실패 단계는 이름 문자열로 담는다.
 *
 * @param shippingFeeAmount 돌려줄 배송비 (원). 거절·철회·승인 불가면 0
 * @param remoteAreaFeeAmount 돌려줄 도서산간 추가 운임 (원). 거절·철회·승인 불가면 0
 * @param deductedShippingFeeAmount 일부 취소로 무료배송 기준이 깨져 환불에서 뺀 기본 배송비 (원). 승인 때 정해진다. 거절·철회·승인 불가면 0
 * @param refundAmount 돌려줄 금액 = 상품 금액 + 배송비 + 추가 운임 − 뺀 배송비 (원). 요청됨일 때는 배송비가 아직 0이라 승인 뒤 바뀔 수 있다.
 *                     거절·철회·승인 불가면 0이다. 배송비 세 칸과 함께 저장 값은 그대로 두고 응답에서만 0으로 바꾼다
 * @param failedStep   처리 중에 멈춘 단계 이름. 멈추지 않았으면 {@code null}
 */
public record RefundResult(
        Long refundId,
        Long orderId,
        Long orderItemId,
        int quantity,
        Long buyerId,
        Long sellerId,
        Long productOptionId,
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
        String failedStep,
        LocalDateTime requestedAt,
        LocalDateTime decidedAt,
        LocalDateTime completedAt
) {

    public static RefundResult from(Refund refund) {
        // 승인 불가는 승인 때 배송비 금액을 저장한 뒤 배송 잠금이 거절되어 끝난다. 돌려주지도 빼지도 않았는데
        // 저장 값을 그대로 보이면 "배송비 3,000원 차감"처럼 읽히므로, 끝난 상태는 금액 칸을 모두 0으로 보인다
        boolean refundsNothing = refund.getStatus().refundsNothing();
        return new RefundResult(
                refund.getId(),
                refund.getOrderId(),
                refund.getOrderItemId(),
                refund.getQuantity(),
                refund.getBuyerId(),
                refund.getSellerId(),
                refund.getProductOptionId(),
                refund.getProductName(),
                refund.getOptionName(),
                refund.getUnitPrice(),
                refund.getItemAmount(),
                refundsNothing ? 0L : refund.getShippingFeeAmount(),
                refundsNothing ? 0L : refund.getRemoteAreaFeeAmount(),
                refundsNothing ? 0L : refund.getDeductedShippingFeeAmount(),
                // 지갑 호출은 처리 중 결과의 금액을 쓰므로, 끝난 상태만 0으로 바꿔도 실제로 돌려주는 돈은 그대로다
                refundsNothing ? 0L : refund.getRefundAmount(),
                refund.getCancelReason(),
                refund.getStatus().name(),
                refund.getRejectReason(),
                refund.getFailedStep() == null ? null : refund.getFailedStep().name(),
                refund.getCreatedAt(),
                refund.getDecidedAt(),
                refund.getCompletedAt());
    }
}
