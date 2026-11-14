package com.ok.refund.adapter.out.order.dto;

import java.util.List;

/**
 * 주문 모듈 {@code GET /internal/v1/orders/{orderId}/items/{orderItemId}} 응답. #533으로 머지된 실제 응답과 같은 칸임을 확인했다.
 * 요청서의 {@code refundedQuantity}는 받지 않는다. 주문은 처리 중인 환불을 몰라 환불이 직접 집계한다(#410 설계 8장).
 * 주문 모듈과의 약속: {@code quantity}(와 {@code sellerGroup.items[].quantity})는 산 개수이고, 환불이 끝나도 줄이지 않는다.
 * 취소된 개수는 취소 수량 반영(요청서 ②) 때 주문의 {@code refundedQuantity}에만 더한다.
 * 환불은 이 값에서 자기 환불 기록(처리 중·완료)의 개수를 직접 빼서 남은 개수를 구한다. 그래서 주문이 {@code quantity}를 줄여 보내면 같은 취소를 두 번 빼게 된다.
 * 예: 감자 3개 중 1개 환불이 끝난 뒤 주문이 3 대신 2를 보내면, 남은 2개 요청이 "2 + 1 > 2"로 409 거절된다.
 * 묶음에서는 감자 1개가 환불된 뒤 0을 보내면 배송비 판단이 "취소 개수가 주문 개수를 넘는다"로 500을 낸다.
 *
 * @param quantity    산 개수. 환불 뒤에도 줄지 않는 값이다(위 약속)
 * @param sellerGroup 이 주문 상품이 속한 판매자 묶음의 배송비 값. 배송비를 돌려줄지·뺄지 판단할 때 쓴다(#410)
 */
public record RefundOrderItemResponse(
        Long orderId,
        Long orderItemId,
        Long buyerId,
        Long sellerId,
        Long productOptionId,
        String productName,
        String optionName,
        String status,
        long unitPrice,
        int quantity,
        SellerGroup sellerGroup
) {

    /**
     * 판매자 묶음 값. 요청서 ①의 {@code sellerGroup} 모양 그대로다.
     *
     * @param shippingFee           구매자가 낸 묶음 배송비 (원). 무료배송이면 0
     * @param remoteAreaFee         구매자가 낸 도서산간 운임 (원)
     * @param baseShippingFee       주문 시점 판매자 기본 배송비 (원)
     * @param freeShippingThreshold 주문 시점 무료배송 기준 금액 (원). 기준이 없으면 {@code null}
     */
    public record SellerGroup(
            long shippingFee,
            long remoteAreaFee,
            long baseShippingFee,
            Long freeShippingThreshold,
            List<Item> items
    ) {
    }

    /**
     * 묶음 안의 주문 상품 한 줄.
     *
     * @param unitPrice 주문 시점 단가 (원)
     * @param quantity  산 개수. 환불 뒤에도 줄지 않는 값이다(클래스 설명의 약속)
     */
    public record Item(Long orderItemId, long unitPrice, int quantity) {
    }
}
