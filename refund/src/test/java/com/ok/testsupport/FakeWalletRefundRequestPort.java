package com.ok.testsupport;

import com.ok.refund.application.port.out.WalletRefundRequest;
import com.ok.refund.application.port.out.WalletRefundRequestPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 지갑 반환 요청 발행(실제 Kafka 어댑터)을 감싸, 평소에는 그대로 실제 토픽에 보내고 테스트가 원할 때만 실패하게 하는 Fake.
 * 주문·상품 Fake처럼 메모리로 대신하지 않는 이유: 발행된 JSON을 실제 토픽에서 읽어 확인하는 통합 테스트(#512)가 있어 실제로 보내야 한다.
 * 실제 어댑터는 패키지 안에서만 보이는 클래스라 타입이 아니라 빈 이름으로 받는다.
 * {@code AbstractIntegrationTest}의 {@code @ComponentScan}이 주워 가고 {@code @Primary}로 실제 어댑터 대신 서비스에 주입된다.
 * 테스트마다 {@link #clear}로 비운다.
 */
@Component
@Primary
public class FakeWalletRefundRequestPort implements WalletRefundRequestPort {

    private final WalletRefundRequestPort kafkaAdapter;
    private volatile boolean failing;
    // 발행이 끝난 직후 한 번 할 일. 승인 응답이 나가기 전에 지갑 결과가 먼저 처리되는 경우를 만들 때 쓴다
    private final AtomicReference<Runnable> afterSend = new AtomicReference<>();

    public FakeWalletRefundRequestPort(@Qualifier("refundWalletKafkaAdapter") WalletRefundRequestPort kafkaAdapter) {
        this.kafkaAdapter = kafkaAdapter;
    }

    /** 실제 어댑터가 브로커 오류·시간 초과를 바꾸는 것처럼, 이후 발행이 보내지 않고 {@link IllegalStateException}을 던지게 한다 */
    public void fail() {
        failing = true;
    }

    /** 다음 발행이 실제로 끝난 직후 {@code action}을 한 번 실행한다(발행한 요청 스레드에서) */
    public void afterNextSend(Runnable action) {
        afterSend.set(action);
    }

    public void clear() {
        failing = false;
        afterSend.set(null);
    }

    @Override
    public void requestRefund(WalletRefundRequest request) {
        if (failing) {
            throw new IllegalStateException("지갑 반환 요청 발행에 실패했습니다(테스트). refundId=" + request.refundId());
        }
        kafkaAdapter.requestRefund(request);
        Runnable action = afterSend.getAndSet(null);
        if (action != null) {
            action.run();
        }
    }
}
