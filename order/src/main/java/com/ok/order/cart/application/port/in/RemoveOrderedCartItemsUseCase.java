package com.ok.order.cart.application.port.in;

import java.util.List;

/**
 * 결제가 끝난 주문의 상품을 장바구니에서 지운다.
 */
public interface RemoveOrderedCartItemsUseCase {

    /**
     * 주문한 옵션을 장바구니에서 지운다. 장바구니가 없으면 아무것도 하지 않는다.
     */
    void removeOrderItems(Long memberId, List<Long> productOptionIds);
}
