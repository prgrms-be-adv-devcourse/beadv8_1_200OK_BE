package com.ok.testsupport;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.service.RefundOrderClient;
import com.ok.refund.service.dto.RefundableOrderItem;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 주문 모듈 내부 API 대신 메모리에 넣어 둔 주문 상품을 돌려주고, 취소 반영 요청을 기록하는 Fake.
 * 주문 팀 API가 아직 없고, 통합 테스트는 웹 서버 없이(MOCK) 돌아 HTTP로 부를 수 없어 대신 쓴다.
 * {@code AbstractIntegrationTest}의 {@code @ComponentScan}이 주워 가고 {@code @Primary}로 실제 클라이언트를 대신한다.
 * 테스트마다 {@link #clear}로 비운다. 동시 요청 테스트가 있어 동시성 컬렉션을 쓴다.
 */
@Component
@Primary
public class FakeRefundOrderClient implements RefundOrderClient {

    private final Map<Long, RefundableOrderItem> items = new ConcurrentHashMap<>();
    private final List<Applied> applied = new CopyOnWriteArrayList<>();

    public void put(RefundableOrderItem item) {
        items.put(item.orderItemId(), item);
    }

    public List<Applied> applied() {
        return List.copyOf(applied);
    }

    public void clear() {
        items.clear();
        applied.clear();
    }

    @Override
    public RefundableOrderItem getOrderItem(Long orderId, Long orderItemId) {
        RefundableOrderItem item = items.get(orderItemId);
        if (item == null || !item.orderId().equals(orderId)) {
            throw new RestApiException(RefundErrorCode.ORDER_ITEM_NOT_FOUND);
        }
        return item;
    }

    @Override
    public void applyRefund(Long refundId, Long orderItemId, int quantity) {
        applied.add(new Applied(refundId, orderItemId, quantity));
    }

    public record Applied(Long refundId, Long orderItemId, int quantity) {
    }
}
