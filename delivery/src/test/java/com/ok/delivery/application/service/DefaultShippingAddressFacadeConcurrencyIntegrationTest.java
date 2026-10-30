package com.ok.delivery.application.service;

import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.ok.delivery.fixture.ShipmentFixture.otherAddressCommand;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 같은 회원의 최초 등록을 동시에 보내 유니크 제약 경합을 만든다. 경합 여부와 상관없이 결과(모두 성공, 행 1개)를 확인한다.
// 경합 시 다시 시도하는 분기 자체는 DefaultShippingAddressFacadeTest가 확인한다.
class DefaultShippingAddressFacadeConcurrencyIntegrationTest extends AbstractIntegrationTest {

    // 두 건만으로는 경합이 드물게 비켜 갈 수 있어 여러 건을 동시에 보낸다
    private static final int THREAD_COUNT = 5;
    private static final Long MEMBER_ID = 1L;

    @Autowired
    DefaultShippingAddressFacade sut;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM default_shipping_address");
    }

    @Test
    void 같은_회원의_최초_등록이_동시에_들어와도_모두_성공하고_한_행만_남는다() {
        // given
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> request = () -> {
            start.await();
            try {
                sut.saveDefaultAddress(MEMBER_ID, otherAddressCommand());
                return "SUCCESS";
            } catch (RuntimeException e) {
                return e.getClass().getSimpleName();
            }
        };

        // when
        List<String> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT)) {
            List<Future<String>> futures = IntStream.range(0, THREAD_COUNT)
                    .mapToObj(i -> executor.submit(request))
                    .toList();
            start.countDown();
            results = futures.stream().map(DefaultShippingAddressFacadeConcurrencyIntegrationTest::await).toList();
        }

        // then
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM default_shipping_address WHERE member_id = ?", Integer.class, MEMBER_ID);
        assertSoftly(softly -> {
            softly.assertThat(results).containsOnly("SUCCESS");
            softly.assertThat(rowCount).isEqualTo(1);
        });
    }

    private static String await(Future<String> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("동시 요청이 끝나지 않았습니다.", e);
        }
    }
}
