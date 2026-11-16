package com.ok.delivery.adapter.in.event.dto;

/**
 * 주문이 Kafka로 보내는 구매확정 메시지를 배송이 읽는 모양. 필드 이름·타입은 주문과의 약속이다.
 * 주문은 정산 금액(sellerId, totalItemPrice, sellerShippingPrice)도 싣지만 배송은 쓰지 않아 받지 않는다. 모르는 필드는 읽을 때 무시된다.
 * orderId는 쓰지 않지만 DLT에 옮겨질 때 사람이 주문을 찾을 수 있게 받아 둔다.
 */
public record OrderProductConfirmedMessage(
        Long orderId,
        Long orderItemId
) {

    /**
     * 구매확정할 주문 상품 ID를 꺼낸다. 다시 받아도 같은 값이므로 틀리면 표준 예외로 알려 다시 시도 없이 DLT로 가게 한다 (exception.md).
     *
     * @throws IllegalArgumentException orderItemId가 없으면
     */
    public Long toOrderItemId() {
        if (orderItemId == null) {
            throw new IllegalArgumentException("orderItemId는 필수입니다. orderId=" + orderId);
        }
        return orderItemId;
    }
}
