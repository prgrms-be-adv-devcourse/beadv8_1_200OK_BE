package com.ok.delivery.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.exception.DeliveryErrorCode;
import com.ok.delivery.repository.ShipmentRepository;
import com.ok.delivery.service.dto.ShipmentDispatchCommand;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.ok.delivery.fixture.ShipmentFixture.item;
import static com.ok.delivery.fixture.ShipmentFixture.shipment;
import static org.assertj.core.api.Assertions.assertThat;

// 송장 등록과 환불의 취소 확정 예약이 동시에 실행돼도 한쪽만 성공하는지 확인한다.
// 예약 서비스는 B9-3에서 만들므로, 여기서는 그 서비스가 할 일(shipment 행 잠금 후 예약)을 트랜잭션으로 직접 실행한다
class ShipmentDispatchConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final int THREAD_COUNT = 2;
    private static final long SELLER_ID = 10L;
    private static final long ORDER_ITEM_ID = 1L;
    private static final String SUCCESS = "SUCCESS";

    @Autowired
    ShipmentService sut;

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    // 실행마다 어느 쪽이 먼저 잠금을 얻을지 달라지므로 여러 번 돌려 두 순서를 모두 거치게 한다
    @RepeatedTest(5)
    void 송장_등록과_취소_확정_예약이_동시에_실행되면_한쪽만_성공한다() throws Exception {
        // given
        Long shipmentId = shipmentRepository.save(shipment(1L, SELLER_ID, item(ORDER_ITEM_ID))).getId();
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> dispatch = () -> {
            start.await();
            return resultOf(() -> sut.dispatch(new ShipmentDispatchCommand(shipmentId, SELLER_ID, "CJ_LOGISTICS", "1234567890")));
        };
        Callable<String> reserve = () -> {
            start.await();
            return resultOf(() -> transactionTemplate.executeWithoutResult(status ->
                    shipmentRepository.findByIdForUpdate(shipmentId).orElseThrow().reserveCancel(ORDER_ITEM_ID, 1)));
        };

        // when
        List<String> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT)) {
            List<Future<String>> futures = List.of(executor.submit(dispatch), executor.submit(reserve));
            start.countDown();
            results = futures.stream().map(ShipmentDispatchConcurrencyIntegrationTest::await).toList();
        }

        // then
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT s.status, i.reserved_cancel_quantity FROM shipment s JOIN shipment_item i ON i.shipment_id = s.id WHERE s.id = ?",
                shipmentId);
        if (SUCCESS.equals(results.get(0))) {
            // 송장 등록이 먼저 잠금을 얻으면 예약은 송장 발행 후라 거절된다
            assertThat(List.of(results.get(1), row.get("status"), row.get("reserved_cancel_quantity")))
                    .containsExactly(DeliveryErrorCode.INVALID_SHIPMENT_STATE.name(), "SHIPPING", 0);
        } else {
            // 예약이 먼저 잠금을 얻으면 송장 등록이 거절된다
            assertThat(List.of(results.get(0), results.get(1), row.get("status"), row.get("reserved_cancel_quantity")))
                    .containsExactly(DeliveryErrorCode.CANCELLATION_RESERVED.name(), SUCCESS, "PREPARING", 1);
        }
    }

    private static String resultOf(Runnable action) {
        try {
            action.run();
            return SUCCESS;
        } catch (RestApiException e) {
            return e.getErrorCode().name();
        }
    }

    private static String await(Future<String> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("동시 요청이 끝나지 않았습니다.", e);
        }
    }
}
