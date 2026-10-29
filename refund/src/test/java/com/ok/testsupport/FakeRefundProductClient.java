package com.ok.testsupport;

import com.ok.refund.service.RefundProductClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 상품 모듈 내부 API 대신 재고 일부 해제 요청을 기록하는 Fake.
 * 테스트마다 {@link #clear}로 비운다. 동시 요청 테스트가 있어 동시성 컬렉션을 쓴다.
 */
@Component
@Primary
public class FakeRefundProductClient implements RefundProductClient {

    private final List<Released> released = new CopyOnWriteArrayList<>();

    public List<Released> released() {
        return List.copyOf(released);
    }

    public void clear() {
        released.clear();
    }

    @Override
    public void releaseStock(Long refundId, Long orderId, Long productOptionId, int quantity) {
        released.add(new Released(refundId, orderId, productOptionId, quantity));
    }

    public record Released(Long refundId, Long orderId, Long productOptionId, int quantity) {
    }
}
