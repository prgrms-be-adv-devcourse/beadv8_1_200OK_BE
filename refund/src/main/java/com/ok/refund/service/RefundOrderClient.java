package com.ok.refund.service;

import com.ok.refund.service.dto.RefundableOrderItem;

/**
 * 주문 모듈을 부른다. 서비스는 이 인터페이스만 알고, HTTP로 부르는 방법은 구현체({@code client} 패키지)가 맡는다.
 * 주문 팀 API가 아직 없어 경로와 응답 모양은 요청서 기준 가정이다.
 */
public interface RefundOrderClient {

    /**
     * 환불 요청을 확인할 때 쓸 주문 상품 정보를 조회한다.
     *
     * @throws com.ok.common.exception.RestApiException 주문 상품이 없으면 {@code ORDER_ITEM_NOT_FOUND},
     *                                                  주문 모듈이 응답하지 않거나 오류 상태를 돌려주면 {@code ORDER_API_FAILED}
     * @throws IllegalStateException                    200인데 본문이 비면 (주문 쪽 버그라 500으로 드러낸다)
     */
    RefundableOrderItem getOrderItem(Long orderId, Long orderItemId);
}
