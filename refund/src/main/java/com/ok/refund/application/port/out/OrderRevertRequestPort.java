package com.ok.refund.application.port.out;

/**
 * 주문 모듈에 환불이 남긴 취소 기록(수량·뺀 배송비)을 되돌려 달라고 요청한다(#622). 서비스는 이 인터페이스만 알고,
 * Kafka로 보내는 방법은 구현체({@code adapter.out.event} 패키지)가 맡는다.
 * 주문은 요청을 받아 되돌린 뒤 결과(되돌림·실패)를 따로 보낸다(PR #537 약속, 주문 #604). 그래서 이 호출은 답을 기다리지 않는다.
 * 취소 반영({@link OrderCancellationPort}, HTTP)과 나눈 이유: 그쪽은 답을 받아 다음을 정해야 해 HTTP로 기다리고,
 * 이쪽은 환불이 이미 실패로 끝내기로 정한 뒤라 Kafka로 보내고 결과만 따로 받는다(주문 팀 합의). 구현 방식이 달라 포트도 나눈다.
 */
public interface OrderRevertRequestPort {

    /**
     * 되돌리기 요청 한 건을 보낸다. 브로커가 저장했다고 확인할 때까지 정해진 시간만큼 기다린다.
     * 기다리는 이유: 확인 없이 돌아오면 브로커가 거절해도 되돌리기 줄이 결과 대기로 남아, 보내지 않은 요청을 다시 보내기까지 늦어진다.
     *
     * @throws IllegalStateException 보내지 못했거나 기다리는 시간이 지났으면. 메시지에 {@code refundId}를 담는다.
     *                               부르는 쪽(실패 기록 틀)이 되돌리기 줄을 할 차례로 되돌리고 이유를 남긴다. 다시 보내기는 알람이 한다
     */
    void requestRevert(OrderRevertRequest request);
}
