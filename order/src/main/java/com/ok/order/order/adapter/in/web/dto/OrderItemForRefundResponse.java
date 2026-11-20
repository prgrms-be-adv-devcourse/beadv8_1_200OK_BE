package com.ok.order.order.adapter.in.web.dto;

import com.ok.order.order.application.dto.OrderItemForRefundResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "환불용 주문 상품 정보")
public record OrderItemForRefundResponse(

        @Schema(description = "주문 ID", example = "10")
        Long orderId,

        @Schema(description = "주문 상품 ID", example = "101")
        Long orderItemId,

        @Schema(description = "구매자 회원 ID. 이 API는 구매자를 확인하지 않으므로 환불이 이 값으로 본인 주문인지 확인한다", example = "1")
        Long buyerId,

        @Schema(description = "판매자 회원 ID", example = "5")
        Long sellerId,

        @Schema(description = "구매한 상품 옵션 ID", example = "55")
        Long productOptionId,

        @Schema(description = "주문 시점 상품명", example = "양말")
        String productName,

        @Schema(description = "주문 시점 옵션명", example = "흰색")
        String optionName,

        @Schema(description = """
                주문 상품 상태 (배송 상태). PAYMENT_PENDING(결제 대기), PAYMENT_FAILED(결제 실패), CANCELED(결제 전 취소),
                PREPARING(배송 준비, 출고 전), SHIPPING(배송 중), DELIVERED(배송 완료), PURCHASE_CONFIRMED(구매확정).
                CANCEL_REQUESTED, REFUNDED는 지금 쓰지 않는다. 환불이 이 값을 문자열로 비교하므로 바꿀 때는 환불 담당과 먼저 협의한다
                """, example = "PREPARING")
        String status,

        @Schema(description = "주문 시점 판매가 (원)", example = "10000")
        long unitPrice,

        @Schema(description = "산 개수", example = "3")
        int quantity,

        @Schema(description = "이 주문 상품이 속한 판매자 묶음")
        OrderItemForRefundSellerGroupResponse sellerGroup
) {
    public static OrderItemForRefundResponse from(OrderItemForRefundResult result) {
        return new OrderItemForRefundResponse(
                result.orderId(),
                result.orderItemId(),
                result.buyerId(),
                result.sellerId(),
                result.productOptionId(),
                result.productName(),
                result.optionName(),
                result.status(),
                result.unitPrice(),
                result.quantity(),
                OrderItemForRefundSellerGroupResponse.from(result.sellerGroup())
        );
    }
}
