package com.ok.order.order.application.port.in;

import com.ok.order.order.application.dto.CheckoutResult;

/**
 * 주문서를 조회한다.
 */
public interface GetCheckoutUseCase {

    /**
     * 장바구니에서 선택한 상품으로 판매자별 상품과 상품 금액 합계를 계산한다. 저장하지 않는다.
     *
     * @throws com.ok.common.exception.RestApiException 선택한 상품이 없으면 {@code EMPTY_ORDER_ITEMS}, 살 수 없는 상품이 있으면 해당 에러 코드
     */
    CheckoutResult getCheckout(Long memberId);
}
