package com.ok.refund.adapter.out.event;

import com.ok.refund.application.port.out.WalletRefundRequest;
import com.ok.refund.application.port.out.WalletRefundRequestPort;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;

/**
 * {@link WalletRefundRequestPort}의 Kafka 구현체. 반환 요청을 {@link RefundTopics#REFUND_APPROVED}로 보낸다.
 * 한 건마다 브로커의 저장 확인(acks=all)을 기다리고, 실패하면 환불 ID를 담은 예외를 던진다.
 * KafkaTemplate은 스프링 부트가 yaml(spring.kafka)로 만든 것을 그대로 쓴다. 환불 전용으로 새로 만들면 한 앱(monolith)에
 * KafkaTemplate이 둘이 되어 다른 모듈의 주입이 모호해질 수 있다.
 */
@Component
@RequiredArgsConstructor
class RefundWalletKafkaAdapter implements WalletRefundRequestPort {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void requestRefund(WalletRefundRequest request) {
        // 키를 orderId로 두면 같은 주문의 요청은 같은 파티션으로 가서 보낸 순서가 지켜진다.
        // 승인 순서까지 지켜 주지는 않는다(승인 커밋 뒤 각 요청 스레드가 따로 보내기 때문). 금액은 환불마다 따로 정해져 순서와 상관없다
        String key = String.valueOf(request.orderId());
        try {
            // 저장 확인을 기다린다. 기다리지 않으면 브로커가 거절해도 판매자는 성공 응답을 받아 실패를 아무도 모른다.
            // 최대 대기 시간은 Kafka 기본값(보낼 자리 대기 max.block.ms 60초, 전체 delivery.timeout.ms 120초)
            kafkaTemplate.send(RefundTopics.REFUND_APPROVED, key, RefundApprovedMessage.from(request)).get();
        } catch (InterruptedException e) {
            // 기다리는 중 스레드가 중단되면 중단 표시를 되살린 뒤 실패로 올린다
            Thread.currentThread().interrupt();
            throw requestFailed(request, e);
        } catch (ExecutionException e) {
            throw requestFailed(request, e.getCause());
        } catch (RuntimeException e) {
            // send()를 부르는 순간 바로 나는 오류(직렬화 실패, 보낼 자리를 기다리다 시간 초과 등)도 어느 환불인지 남긴다
            throw requestFailed(request, e);
        }
    }

    // 사용자 입력이 아니라 브로커 쪽 실패라 표준 예외를 쓴다 (exception.md: 값의 출처 기준). 응답은 500이 된다
    private static IllegalStateException requestFailed(WalletRefundRequest request, Throwable cause) {
        return new IllegalStateException("지갑 반환 요청 발행에 실패했습니다. refundId=" + request.refundId(), cause);
    }
}
