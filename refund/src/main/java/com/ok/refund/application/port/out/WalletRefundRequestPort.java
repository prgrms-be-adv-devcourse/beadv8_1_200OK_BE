package com.ok.refund.application.port.out;

/**
 * 지갑 모듈에 예치금 반환을 요청한다. 서비스는 이 인터페이스만 알고, Kafka로 보내는 방법은 구현체({@code adapter.out.event} 패키지)가 맡는다.
 * 지갑은 요청을 받아 구매자에게 돈을 돌려주고 완료 메시지를 따로 보낸다(#513에서 받음). 그래서 이 호출은 답을 기다리지 않는다.
 */
public interface WalletRefundRequestPort {

    /**
     * 반환 요청 한 건을 보낸다. 브로커가 저장했다고 확인할 때까지 정해진 시간만큼 기다린다.
     * 기다리는 이유: 확인 없이 돌아오면 브로커가 거절해도 판매자는 성공 응답을 받아, 요청이 나가지 않은 환불을 아무도 모르게 된다.
     *
     * @throws IllegalStateException 보내지 못했거나 기다리는 시간이 지났으면. 메시지에 {@code refundId}를 담는다.
     *                               부르는 쪽(실패 기록 틀)이 지갑 단계 줄을 할 차례로 되돌리고 이유를 남긴다(#574). 다시 보내기는 #576이 한다.
     *                               던지는 쪽은 로그를 따로 남기지 않는다(service.md, {@code ApiControllerAdvice}가 오류 로그를 남긴다)
     */
    void requestRefund(WalletRefundRequest request);
}
