package com.ok.order.order.adapter.in.web.dto;

import com.ok.order.order.application.dto.OrderItemForRefundSellerGroupResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "판매자 묶음의 배송비 값. 환불이 배송비를 돌려줄지·뺄지 판단할 때 쓴다")
public record OrderItemForRefundSellerGroupResponse(

        @Schema(description = "구매자가 낸 묶음 배송비 (원). 무료배송이면 0", example = "3000")
        long shippingFee,

        @Schema(description = "구매자가 낸 도서산간 추가 운임 (원)", example = "0")
        long remoteAreaFee,

        @Schema(description = "주문 시점 판매자 기본 배송비 (원). 무료배송이어도 설정 값이다", example = "3000")
        long baseShippingFee,

        @Schema(description = "주문 시점 무료배송 기준 금액 (원). null이면 무료배송 없음", example = "50000", nullable = true)
        Long freeShippingThreshold,

        @Schema(description = "묶음 안의 모든 주문 상품 (조회한 주문 상품 포함)")
        List<OrderItemForRefundGroupItemResponse> items
) {
    public static OrderItemForRefundSellerGroupResponse from(OrderItemForRefundSellerGroupResult result) {
        return new OrderItemForRefundSellerGroupResponse(
                result.shippingFee(),
                result.remoteAreaFee(),
                result.baseShippingFee(),
                result.freeShippingThreshold(),
                result.items().stream().map(OrderItemForRefundGroupItemResponse::from).toList()
        );
    }
}
