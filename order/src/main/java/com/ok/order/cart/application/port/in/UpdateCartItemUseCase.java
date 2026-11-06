package com.ok.order.cart.application.port.in;

/**
 * 장바구니 상품의 수량과 선택 상태를 바꾼다.
 */
public interface UpdateCartItemUseCase {

    /**
     * 내 장바구니 상품의 수량을 주어진 값으로 바꾼다. 더하는 것이 아니라 덮어쓴다.
     *
     * @throws com.ok.common.exception.RestApiException 내 장바구니 상품이 아니면 {@code CART_ITEM_NOT_FOUND}, 수량이 범위를 벗어나면 {@code INVALID_QUANTITY}
     */
    void changeQuantity(Long memberId, Long cartItemId, int quantity);

    /**
     * 내 장바구니 상품 하나를 주문할 상품으로 선택하거나 해제한다.
     *
     * @throws com.ok.common.exception.RestApiException 내 장바구니 상품이 아니면 {@code CART_ITEM_NOT_FOUND}
     */
    void changeSelected(Long memberId, Long cartItemId, boolean selected);

    void changeAllSelected(Long memberId, boolean selected);
}
