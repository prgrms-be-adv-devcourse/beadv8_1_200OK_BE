package com.ok.order.order.controller.dto;

import com.ok.order.order.service.dto.OrderDetailResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "주문 상세")
public record OrderDetailResponse(
        @Schema(description = "주문 ID", example = "15") Long orderId,
        @Schema(description = "주문 상태 (PAYMENT_PENDING, PAID, PAYMENT_FAILED, CANCELED)", example = "PAID") String status,
        @Schema(description = "주문 시각") LocalDateTime orderedAt,
        @Schema(description = "수령인") String recipientName,
        @Schema(description = "수령인 연락처", example = "010-1234-5678") String recipientPhone,
        @Schema(description = "우편번호", example = "12345") String zipCode,
        @Schema(description = "주소") String address,
        @Schema(description = "상세 주소") String addressDetail,
        @Schema(description = "판매자별 묶음") List<OrderDetailSellerGroupResponse> sellerGroups,
        @Schema(description = "상품 금액 합계") long totalProductAmount,
        @Schema(description = "배송비 합계") long totalShippingFee,
        @Schema(description = "도서산간 추가 운임 합계") long totalRemoteAreaFee,
        @Schema(description = "총 결제 금액") long totalAmount
) {
    public static OrderDetailResponse from(OrderDetailResult result) {
        return new OrderDetailResponse(
                result.orderId(),
                result.status(),
                result.orderedAt(),
                result.recipientName(),
                result.recipientPhone(),
                result.zipCode(),
                result.address(),
                result.addressDetail(),
                result.sellerGroups().stream().map(OrderDetailSellerGroupResponse::from).toList(),
                result.totalProductAmount(),
                result.totalShippingFee(),
                result.totalRemoteAreaFee(),
                result.totalAmount()
        );
    }
}