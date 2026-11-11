package com.ok.refund.adapter.out.event;

import com.ok.refund.application.port.out.WalletRefundRequest;
import com.ok.refund.application.port.out.WalletRefundRequestPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link WalletRefundRequestPort}의 Kafka 구현체. 반환 요청을 {@link RefundTopics#REFUND_APPROVED}로 보낸다.
 * 한 건마다 브로커의 저장 확인(acks=all)을 최대 {@link #SEND_TIMEOUT}까지 기다리고, 실패하면 환불 ID를 담은 예외를 던진다.
 * KafkaTemplate은 스프링 부트가 yaml(spring.kafka)로 만든 것을 그대로 쓴다. 환불 전용으로 새로 만들면 한 앱(monolith)에
 * KafkaTemplate이 둘이 되어 다른 모듈의 주입이 모호해질 수 있다.
 */
@Component
class RefundWalletKafkaAdapter implements WalletRefundRequestPort {

    // 저장 확인을 기다리는 최대 시간. 판매자의 승인 요청 안에서 기다리므로, Kafka 기본값(delivery.timeout.ms 120초)까지
    // 기다리면 브로커 장애 때 요청 스레드(톰캣)를 오래 붙잡아 다른 요청까지 느려질 수 있다. 정상이면 1초 안에 끝난다
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Duration sendTimeout;

    @Autowired
    RefundWalletKafkaAdapter(KafkaTemplate<String, Object> kafkaTemplate) {
        this(kafkaTemplate, SEND_TIMEOUT);
    }

    // 테스트가 시간 초과 경로를 10초씩 기다리지 않고 확인할 수 있게 대기 시간을 바꿔 넣는다
    RefundWalletKafkaAdapter(KafkaTemplate<String, Object> kafkaTemplate, Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void requestRefund(WalletRefundRequest request) {
        // 키를 orderId로 두면 같은 주문의 요청은 같은 파티션으로 가서 보낸 순서가 지켜진다.
        // 승인 순서까지 지켜 주지는 않는다(승인 커밋 뒤 각 요청 스레드가 따로 보내기 때문). 금액은 환불마다 따로 정해져 순서와 상관없다
        String key = String.valueOf(request.orderId());
        try {
            // 저장 확인을 기다린다. 기다리지 않으면 브로커가 거절해도 판매자는 성공 응답을 받아 실패를 아무도 모른다.
            // 이 시간 제한은 send() 뒤의 기다림만 줄인다. send() 안에서 Kafka 주소를 찾느라 기다리는 시간(max.block.ms 기본 60초)은
            // 그대로다. 그 시간은 producer 설정이라 #516에서 함께 다룬다
            kafkaTemplate.send(RefundTopics.REFUND_APPROVED, key, RefundApprovedMessage.from(request))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 기다리는 중 스레드가 중단되면 중단 표시를 되살린 뒤 실패로 올린다
            Thread.currentThread().interrupt();
            throw requestFailed(request, e);
        } catch (ExecutionException e) {
            throw requestFailed(request, e.getCause());
        } catch (TimeoutException e) {
            // 기다리기만 그만둔 것이라 메시지는 뒤에서 계속 보내질 수 있다. 그래서 "판매자는 500인데 실제로는 지갑에 간" 경우가 생길 수 있다.
            // 환불은 어차피 처리 중으로 남아 상태가 꼬이지 않고, 지갑은 refundId로 중복을 가른다(#413 약속)
            throw requestFailed(request, e);
        } catch (RuntimeException e) {
            // send()를 부르는 순간 바로 나는 오류(직렬화 실패, 보낼 자리를 기다리다 시간 초과 등)도 어느 환불인지 남긴다
            throw requestFailed(request, e);
        }
    }

    // 사용자 입력이 아니라 브로커 쪽 실패라 표준 예외를 쓴다 (exception.md: 값의 출처 기준). 응답은 500이 되고 ApiControllerAdvice가 오류 로그를 남긴다
    private static IllegalStateException requestFailed(WalletRefundRequest request, Throwable cause) {
        return new IllegalStateException("지갑 반환 요청 발행에 실패했습니다. refundId=" + request.refundId(), cause);
    }
}
