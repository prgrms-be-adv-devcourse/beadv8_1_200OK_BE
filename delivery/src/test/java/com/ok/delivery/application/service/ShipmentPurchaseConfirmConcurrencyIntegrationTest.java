package com.ok.delivery.application.service;

import com.ok.delivery.adapter.out.persistence.ShipmentJpaRepository;
import com.ok.delivery.application.port.in.ShipmentUseCase;
import com.ok.delivery.domain.Carrier;
import com.ok.delivery.domain.Shipment;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.ok.delivery.fixture.ShipmentFixture.NOW;
import static com.ok.delivery.fixture.ShipmentFixture.item;
import static com.ok.delivery.fixture.ShipmentFixture.shipment;
import static org.assertj.core.api.Assertions.assertThat;

// 같은 shipment의 두 항목이 동시에 구매확정되어도 뒤 트랜잭션이 앞 확정을 보고 거래완료로 바꾸는지 확인한다 (#644).
// 잠금 조회가 항목을 잠금 없이 읽으면 그 읽기가 REPEATABLE READ 스냅숏을 잠금 대기 전에 고정해, 두 항목 모두 확정인데 DELIVERED에 남았다
class ShipmentPurchaseConfirmConcurrencyIntegrationTest extends AbstractIntegrationTest {

    // 경합은 실행마다 갈리므로 한 번으로는 잡히지 않을 수 있어 여러 쌍을 돌린다
    private static final int PAIR_COUNT = 50;
    // 이 클래스 전용 번호대. 주문 상품 번호와 송장번호가 다른 테스트와 겹치지 않게 한다
    private static final AtomicLong SEQ = new AtomicLong(9_699_000L);

    @Autowired
    ShipmentUseCase sut;

    @Autowired
    ShipmentJpaRepository shipmentRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    @Test
    void 같은_shipment의_두_항목을_동시에_구매확정하면_거래완료가_된다() throws Exception {
        // given
        List<long[]> pairs = new ArrayList<>();
        for (int n = 0; n < PAIR_COUNT; n++) {
            pairs.add(saveDeliveredShipmentWithTwoItems());
        }

        // when
        List<String> notCompleted = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (long[] pair : pairs) {
                confirmAtOnce(executor, pair[1], pair[2]);
                Map<String, Object> row = jdbcTemplate.queryForMap("""
                        SELECT s.status, SUM(i.confirmed) AS confirmed_count FROM shipment s JOIN shipment_item i ON i.shipment_id = s.id
                        WHERE s.id = ? GROUP BY s.status""", pair[0]);
                if (!"COMPLETED".equals(row.get("status")) || ((Number) row.get("confirmed_count")).intValue() != 2) {
                    notCompleted.add("shipmentId=" + pair[0] + " " + row);
                }
            }
        }

        // then
        assertThat(notCompleted).as("%d쌍 중 거래완료가 되지 않은 쌍", PAIR_COUNT).isEmpty();
    }

    private void confirmAtOnce(ExecutorService executor, long firstOrderItemId, long secondOrderItemId) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Future<?> first = executor.submit(() -> confirmAfter(start, firstOrderItemId));
        Future<?> second = executor.submit(() -> confirmAfter(start, secondOrderItemId));
        start.countDown();
        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);
    }

    private void confirmAfter(CountDownLatch start, long orderItemId) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        sut.confirmPurchase(orderItemId);
    }

    // 항목 2개짜리 배송 완료 shipment를 저장하고 {shipmentId, 첫 주문 상품 번호, 둘째 주문 상품 번호}를 돌려준다
    private long[] saveDeliveredShipmentWithTwoItems() {
        long firstOrderItemId = SEQ.incrementAndGet();
        long secondOrderItemId = SEQ.incrementAndGet();
        Shipment shipment = shipment(firstOrderItemId * 10, 10L, item(firstOrderItemId), item(secondOrderItemId));
        shipment.dispatch(Carrier.CJ_LOGISTICS, "100" + firstOrderItemId, NOW);
        shipment.deliver(NOW);
        long shipmentId = shipmentRepository.save(shipment).getId();
        return new long[]{shipmentId, firstOrderItemId, secondOrderItemId};
    }
}
