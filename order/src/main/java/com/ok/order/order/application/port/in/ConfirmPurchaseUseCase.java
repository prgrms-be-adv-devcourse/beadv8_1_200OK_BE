package com.ok.order.order.application.port.in;

/**
 * 구매자가 주문 상품을 구매확정한다.
 */
public interface ConfirmPurchaseUseCase {

    /**
     * 배송 완료된 내 주문 상품을 구매확정하고 정산용 이벤트를 발행한다.
     *
     * @throws com.ok.common.exception.RestApiException 주문·주문 상품이 없으면 {@code ORDER_NOT_FOUND}, {@code ORDER_ITEM_NOT_FOUND},
     *                                                  이미 확정됐으면 {@code ALREADY_PURCHASE_CONFIRMED}, 배송 완료가 아니면 {@code PURCHASE_CONFIRM_NOT_ALLOWED}
     */
    void confirmPurchase(Long buyerId, Long orderId, Long orderItemId);
}
