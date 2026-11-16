package com.ok.order.order.adapter.out.event.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code order.order-paid.v1} 토픽에 보내는 결제 완료(배송 요청) 메시지. 필드 이름·타입이 배송과의 약속이다(#603, 배송 #644).
 * 배송은 이 클래스가 아니라 같은 JSON 모양의 자기 DTO로 읽는다. 필드를 바꾸면 배송도 같이 바꿔야 한다.
 *
 * @param paidAt       결제 완료를 반영한 시각. 오프셋 없는 ISO-8601(Asia/Seoul)로 나간다
 * @param sellerOrders 판매자 묶음마다 배송 1건이 된다
 */
public record OrderPaidMessage(
        Long orderId,
        Long buyerId,
        LocalDateTime paidAt,
        ShippingAddress shippingAddress,
        List<SellerOrder> sellerOrders
) {

    /**
     * @param postalCode 우편번호. 주문 안의 이름은 zipCode지만 배송과 약속한 이름으로 보낸다
     */
    public record ShippingAddress(
            String recipientName,
            String recipientPhone,
            String postalCode,
            String address,
            String addressDetail,
            String deliveryNote
    ) {
    }

    /**
     * @param baseShippingFee       판매자 설정의 기본 배송비(원). 무료배송이어도 설정 값
     * @param freeShippingThreshold 무료배송 기준 금액(원). null이면 무료배송 없음
     * @param regionSurcharge       도서산간 추가 운임(원)
     */
    public record SellerOrder(
            Long sellerId,
            Long baseShippingFee,
            Long freeShippingThreshold,
            Long regionSurcharge,
            List<Item> items
    ) {
    }

    /**
     * 상품명·옵션명·단가는 주문 시점 값이다.
     *
     * @param unitPrice 단가(원)
     */
    public record Item(
            Long orderItemId,
            String productName,
            String optionName,
            int quantity,
            long unitPrice
    ) {
    }
}
