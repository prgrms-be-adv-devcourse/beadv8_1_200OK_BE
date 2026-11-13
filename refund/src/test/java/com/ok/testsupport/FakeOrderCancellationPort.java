package com.ok.testsupport;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.out.OrderCancellation;
import com.ok.refund.application.port.out.OrderCancellationPort;
import com.ok.refund.application.port.out.OrderCancellationResult;
import com.ok.refund.exception.RefundErrorCode;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주문 모듈 취소 반영 API(주문 ②, #542) 대신 반영된 값을 메모리에 남기는 Fake.
 * 통합 테스트는 웹 서버 없이(MOCK) 돌아 HTTP로 부를 수 없어 대신 쓴다.
 * 실제 주문처럼 같은 refundId·같은 내용으로 다시 오면 더하지 않고 반영됨으로 답하고(출고 표시보다 먼저 본다),
 * 같은 refundId인데 내용이 다르거나 {@link #markShipped}로 출고 표시한 상품이면 거절됨으로 답한다.
 * {@code AbstractIntegrationTest}의 {@code @ComponentScan}이 주워 가고 {@code @Primary}로 실제 어댑터를 대신한다.
 * 테스트마다 {@link #clear}로 비운다. 동시 요청 테스트에서도 쓰도록 동시성 컬렉션을 쓴다.
 */
@Component
@Primary
public class FakeOrderCancellationPort implements OrderCancellationPort {

    private final Map<Long, OrderCancellation> reflected = new ConcurrentHashMap<>();
    private final Set<Long> shippedOrderItemIds = ConcurrentHashMap.newKeySet();
    private volatile boolean failing;

    /** 실제 어댑터가 주문 서버 꺼짐·타임아웃·오류 응답을 바꾸는 것처럼, 이후 호출이 {@code ORDER_API_FAILED}를 던지게 한다 */
    public void fail() {
        failing = true;
    }

    /** 이 주문 상품은 이미 출고된 것으로 둔다. 이후 처음 오는 취소 반영은 거절됨으로 답한다 */
    public void markShipped(Long orderItemId) {
        shippedOrderItemIds.add(orderItemId);
    }

    public void clear() {
        reflected.clear();
        shippedOrderItemIds.clear();
        failing = false;
    }

    /** 이 환불로 주문에 반영된 값. 반영되지 않았으면 비어 있다 */
    public Optional<OrderCancellation> reflectedOf(Long refundId) {
        return Optional.ofNullable(reflected.get(refundId));
    }

    @Override
    public OrderCancellationResult reflectCancellation(OrderCancellation cancellation) {
        if (failing) {
            throw new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        }
        // 이미 반영된 refundId는 출고 표시보다 먼저 본다. 반영 뒤에 출고돼도 같은 요청을 다시 받으면 반영됨으로 답해야 하기 때문이다
        OrderCancellation previous = reflected.get(cancellation.refundId());
        if (previous != null) {
            if (previous.equals(cancellation)) {
                return OrderCancellationResult.reflected();
            }
            return OrderCancellationResult.rejected("같은 refundId로 다른 내용의 요청이 이미 반영되어 있습니다.");
        }
        if (shippedOrderItemIds.contains(cancellation.orderItemId())) {
            return OrderCancellationResult.rejected("이미 출고된 상품입니다.");
        }
        // 동시에 같은 refundId가 두 번 와도 먼저 들어간 값 하나만 남긴다
        reflected.putIfAbsent(cancellation.refundId(), cancellation);
        return OrderCancellationResult.reflected();
    }
}
