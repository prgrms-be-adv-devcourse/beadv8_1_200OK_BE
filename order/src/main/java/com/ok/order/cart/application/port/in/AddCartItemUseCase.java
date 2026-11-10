package com.ok.order.cart.application.port.in;

import com.ok.order.cart.application.dto.AddCartItemResult;
import com.ok.order.cart.domain.CartItem;

/**
 * 장바구니에 상품 옵션을 담는다.
 */
public interface AddCartItemUseCase {

    // 요청 검증(@Max)이 같은 상한을 쓰도록 열어 둔다. 입력 어댑터는 도메인을 직접 알지 않는다
    int MAX_QUANTITY = CartItem.MAX_QUANTITY;

    /**
     * 상품 옵션을 담는다. 이미 담긴 옵션이면 수량을 더한다(최대 {@link #MAX_QUANTITY}).
     *
     * @throws com.ok.common.exception.RestApiException 수량이 범위를 벗어나면 {@code INVALID_QUANTITY}
     */
    AddCartItemResult addItem(Long memberId, Long productOptionId, int quantity);
}
