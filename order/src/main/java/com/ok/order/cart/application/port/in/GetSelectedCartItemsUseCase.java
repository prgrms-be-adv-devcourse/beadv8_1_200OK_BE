package com.ok.order.cart.application.port.in;

import com.ok.order.cart.application.dto.SelectedCartItem;

import java.util.List;

/**
 * 주문할 상품(장바구니에서 선택한 상품)을 조회한다. 주문서 조회와 주문 생성에서 쓴다.
 */
public interface GetSelectedCartItemsUseCase {

    /**
     * 선택한 장바구니 상품의 옵션 ID와 수량을 조회한다. 장바구니가 없으면 빈 목록이다.
     */
    List<SelectedCartItem> getSelectedCartItems(Long memberId);
}
