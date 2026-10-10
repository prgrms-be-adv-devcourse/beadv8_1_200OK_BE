package com.ok.delivery.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.domain.AddressChangeStatus;
import com.ok.delivery.exception.DeliveryErrorCode;
import com.ok.delivery.repository.ShipmentRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.*;

import static com.ok.delivery.fixture.ShipmentFixture.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class ShipmentAddressChangeServiceConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREAD_COUNT = 2;

    @Autowired
    ShipmentAddressChangeService sut;

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM shipment_address_change_request");
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    @Test
    void 동시에_두_건을_요청해도_PENDING은_한_건만_생긴다() throws Exception {
        // given
        Long shipmentId = shipmentRepository.save(shipment(item(1L))).getId();
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> request = () -> {
            start.await();
            try {
                sut.request(shipmentId, otherAddress(), 0);
                return "SUCCESS";
            } catch (RestApiException e) {
                return e.getErrorCode().name();
            }
        };

        // when
        List<String> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT)) {
            List<Future<String>> futures = List.of(executor.submit(request), executor.submit(request));
            start.countDown();
            results = futures.stream().map(ShipmentAddressChangeServiceConcurrencyTest::await).toList();
        }

        // then
        Integer pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shipment_address_change_request WHERE shipment_id = ? AND status = ?",
                Integer.class, shipmentId, AddressChangeStatus.PENDING.name());
        assertSoftly(softly -> {
            softly.assertThat(results)
                    .containsExactlyInAnyOrder("SUCCESS", DeliveryErrorCode.ADDRESS_CHANGE_ALREADY_PENDING.name());
            softly.assertThat(pendingCount).isEqualTo(1);
        });
    }

    @Test
    void 없는_shipment에는_요청할_수_없다() {
        // when & then
        assertThatThrownBy(() -> sut.request(Long.MAX_VALUE, otherAddress(), 0))
                .isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(DeliveryErrorCode.SHIPMENT_NOT_FOUND);
    }

    private static String await(Future<String> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("동시 요청이 끝나지 않았습니다.", e);
        }
    }
}
