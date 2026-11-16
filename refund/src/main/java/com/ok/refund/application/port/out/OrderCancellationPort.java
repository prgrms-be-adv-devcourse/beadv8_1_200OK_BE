package com.ok.refund.application.port.out;

/**
 * 주문 모듈에 환불로 취소된 수량과 뺀 배송비를 알린다(주문 ②). 서비스는 이 인터페이스만 알고, HTTP로 부르는 방법은 구현체가 맡는다.
 * 주문은 이 값으로 구매확정 금액에서 취소분을 빼고, 뺀 배송비를 판매자 몫으로 정산에 보낸다(주문팀 합의).
 * 조회({@link OrderQueryPort})와 나눈 이유: 조회는 승인 전에, 이 호출은 승인 커밋 뒤에 쓰여 부르는 시점과 실패 의미가 다르다.
 * 주문 실제 API(#542)는 {@code POST /internal/v1/orders/items/{orderItemId}/refunds}, 본문 refundId·quantity·deductedShippingFee,
 * 성공 204이고 반영할 수 없으면 409(이미 출고됨, 수량 초과, 같은 refundId인데 내용이 다름) 세 가지로 답한다.
 */
public interface OrderCancellationPort {

    /**
     * 취소 반영을 한 번 요청한다. 같은 refundId·같은 내용으로 다시 보내면 주문은 더하지 않고 성공으로 답한다.
     * 주문이 반영을 막으면(거절) 예외가 아니라 {@code rejected} 결과로 돌려준다.
     *
     * @throws com.ok.common.exception.RestApiException 주문 모듈이 응답하지 않거나 거절이 아닌 오류 상태를 돌려주면 {@code ORDER_API_FAILED}
     */
    OrderCancellationResult reflectCancellation(OrderCancellation cancellation);
}
