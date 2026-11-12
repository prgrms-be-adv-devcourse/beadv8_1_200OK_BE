package com.ok.testsupport;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.out.OrderCancellation;
import com.ok.refund.application.port.out.OrderCancellationPort;
import com.ok.refund.exception.RefundErrorCode;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주문 모듈 취소 반영 API(주문 ②) 대신 반영된 값을 메모리에 남기는 Fake.
 * 주문 팀 API(#28)가 아직 없고, 통합 테스트는 웹 서버 없이(MOCK) 돌아 HTTP로 부를 수 없어 대신 쓴다.
 * 요청서 ②처럼 같은 refundId로 다시 오면 더하지 않는다(처음 반영한 값을 그대로 둔다).
 * {@code AbstractIntegrationTest}의 {@code @ComponentScan}이 주워 가고 {@code @Primary}로 실제 어댑터를 대신한다.
 * 테스트마다 {@link #clear}로 비운다. 동시 요청 테스트에서도 쓰도록 동시성 컬렉션을 쓴다.
 */
@Component
@Primary
public class FakeOrderCancellationPort implements OrderCancellationPort {

    private final Map<Long, OrderCancellation> reflected = new ConcurrentHashMap<>();
    private volatile boolean failing;

    /** 실제 어댑터가 주문 서버 꺼짐·타임아웃·오류 응답을 바꾸는 것처럼, 이후 호출이 {@code ORDER_API_FAILED}를 던지게 한다 */
    public void fail() {
        failing = true;
    }

    public void clear() {
        reflected.clear();
        failing = false;
    }

    /** 이 환불로 주문에 반영된 값. 반영되지 않았으면 비어 있다 */
    public Optional<OrderCancellation> reflectedOf(Long refundId) {
        return Optional.ofNullable(reflected.get(refundId));
    }

    @Override
    public void reflectCancellation(OrderCancellation cancellation) {
        if (failing) {
            throw new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        }
        reflected.putIfAbsent(cancellation.refundId(), cancellation);
    }
}
