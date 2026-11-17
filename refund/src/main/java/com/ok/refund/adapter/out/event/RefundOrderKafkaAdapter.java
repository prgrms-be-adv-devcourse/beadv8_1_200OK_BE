package com.ok.refund.adapter.out.event;

import com.ok.refund.application.port.out.OrderRevertRequest;
import com.ok.refund.application.port.out.OrderRevertRequestPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link OrderRevertRequestPort}의 Kafka 구현체. 주문 취소 기록 되돌리기 요청을 {@link RefundTopics#REFUND_ORDER_REVERT_REQUESTED}로 보낸다(#622).
 * 저장 확인을 기다리는 방식과 실패 처리는 지갑 발행({@link RefundWalletKafkaAdapter})과 같다.
 * 지갑 어댑터에 넣지 않고 따로 둔 이유: 받는 모듈(주문)이 달라, 지갑 포트를 구현하는 클래스가 주문 포트까지 구현하면 이름과 맡은 일이 어긋난다.
 * 보내는 코드(약 20줄)는 지갑 어댑터와 겹치지만, 이미 테스트가 붙은 지갑 어댑터를 이번에 고치지 않으려고 나누지 않았다.
 */
@Component
class RefundOrderKafkaAdapter implements OrderRevertRequestPort {

    // 지갑 발행과 같은 값이다. 되돌리기는 승인 요청 안이 아니라 메시지 수신·알람에서 보내지만, 같은 이유(브로커 장애 때 스레드를 오래 잡지 않기)로 짧게 둔다
    static final Duration SEND_TIMEOUT = RefundWalletKafkaAdapter.SEND_TIMEOUT;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Duration sendTimeout;

    @Autowired
    RefundOrderKafkaAdapter(KafkaTemplate<String, Object> kafkaTemplate) {
        this(kafkaTemplate, SEND_TIMEOUT);
    }

    // 테스트가 시간 초과 경로를 오래 기다리지 않고 확인할 수 있게 대기 시간을 바꿔 넣는다
    RefundOrderKafkaAdapter(KafkaTemplate<String, Object> kafkaTemplate, Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeout = sendTimeout;
    }

    // 키를 orderId로 둔다(PR #537 약속). 같은 주문의 메시지가 같은 파티션으로 가 보낸 순서가 지켜진다
    @Override
    public void requestRevert(OrderRevertRequest request) {
        String key = String.valueOf(request.orderId());
        String failureMessage = "주문 되돌리기 요청 발행에 실패했습니다. refundId=" + request.refundId();
        try {
            kafkaTemplate.send(RefundTopics.REFUND_ORDER_REVERT_REQUESTED, key, RefundOrderRevertRequestedMessage.from(request))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 기다리는 중 스레드가 중단되면 중단 표시를 되살린 뒤 실패로 올린다
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(failureMessage, e.getCause());
        } catch (TimeoutException e) {
            // 기다리기만 그만둔 것이라 메시지는 뒤에서 계속 보내질 수 있다. 주문은 refundId로 중복을 가르므로 다시 보내도 한 번만 되돌린다
            throw new IllegalStateException(failureMessage, e);
        } catch (RuntimeException e) {
            // send()를 부르는 순간 바로 나는 오류(직렬화 실패 등)도 어느 환불인지 남긴다. 브로커 쪽 실패라 표준 예외를 쓴다(exception.md)
            throw new IllegalStateException(failureMessage, e);
        }
    }
}
