package com.ok.order.cart.application.port.in;

/**
 * 장바구니 상품을 지운다.
 */
public interface DeleteCartItemUseCase {

    /**
     * 내 장바구니 상품 하나를 지운다.
     *
     * @throws com.ok.common.exception.RestApiException 내 장바구니 상품이 아니면 {@code CART_ITEM_NOT_FOUND}
     */
    void deleteItem(Long memberId, Long cartItemId);

    void deleteSelectedItems(Long memberId);
}
