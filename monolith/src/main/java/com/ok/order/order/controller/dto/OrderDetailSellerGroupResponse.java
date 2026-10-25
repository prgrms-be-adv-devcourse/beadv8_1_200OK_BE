package com.ok.order.order.controller.dto;

import com.ok.order.order.service.dto.OrderDetailSellerGroupResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "주문 상세의 판매자별 묶음")
public record OrderDetailSellerGroupResponse(
        @Schema(description = "판매자 ID") Long sellerId,
        @Schema(description = "묶음 배송비") long shippingFee,
        @Schema(description = "도서산간 추가 운임") long remoteAreaFee,
        @Schema(description = "이 판매자의 주문 상품") List<OrderDetailItemResponse> items
) {
    public static OrderDetailSellerGroupResponse from(OrderDetailSellerGroupResult result) {
        return new OrderDetailSellerGroupResponse(
                result.sellerId(),
                result.shippingFee(),
                result.remoteAreaFee(),
                result.items().stream().map(OrderDetailItemResponse::from).toList()
        );
    }
}