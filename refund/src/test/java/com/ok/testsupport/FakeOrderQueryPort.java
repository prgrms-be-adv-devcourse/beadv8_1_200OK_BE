package com.ok.testsupport;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.application.port.out.OrderQueryPort;
import com.ok.refund.application.port.out.RefundableOrderItem;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주문 모듈 내부 API 대신 메모리에 넣어 둔 주문 상품을 돌려주는 Fake.
 * 주문 팀 API가 아직 없고, 통합 테스트는 웹 서버 없이(MOCK) 돌아 HTTP로 부를 수 없어 대신 쓴다.
 * {@code AbstractIntegrationTest}의 {@code @ComponentScan}이 주워 가고 {@code @Primary}로 실제 클라이언트를 대신한다.
 * 테스트마다 {@link #clear}로 비운다. 동시 요청 테스트에서도 쓰도록 동시성 컬렉션을 쓴다.
 */
@Component
@Primary
public class FakeOrderQueryPort implements OrderQueryPort {

    private final Map<Long, RefundableOrderItem> items = new ConcurrentHashMap<>();
    private volatile boolean failing;

    public void put(RefundableOrderItem item) {
        items.put(item.orderItemId(), item);
    }

    /** 실제 클라이언트가 주문 서버 꺼짐·타임아웃·5xx를 바꾸는 것처럼, 이후 호출이 {@code ORDER_API_FAILED}를 던지게 한다 */
    public void fail() {
        failing = true;
    }

    public void clear() {
        items.clear();
        failing = false;
    }

    @Override
    public RefundableOrderItem getOrderItem(Long orderId, Long orderItemId) {
        if (failing) {
            throw new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        }
        // 실제 주문 API도 주문 ID와 주문 상품 ID가 맞지 않으면 404를 준다고 가정했다
        RefundableOrderItem item = items.get(orderItemId);
        if (item == null || !item.orderId().equals(orderId)) {
            throw new RestApiException(RefundErrorCode.ORDER_ITEM_NOT_FOUND);
        }
        return item;
    }
}
