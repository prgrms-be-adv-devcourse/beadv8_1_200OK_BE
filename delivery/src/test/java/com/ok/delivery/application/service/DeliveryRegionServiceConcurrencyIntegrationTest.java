package com.ok.delivery.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.application.dto.PostalRegionRegisterCommand;
import com.ok.delivery.exception.DeliveryErrorCode;
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

import static org.assertj.core.api.SoftAssertions.assertSoftly;

class DeliveryRegionServiceConcurrencyIntegrationTest extends AbstractIntegrationTest {

    // 초기 데이터에 없는 번호라 테스트 행이 시드와 겹치지 않는다
    private static final String TEST_POSTAL_CODE = "99996";
    private static final Long ADMIN_ID = 1L;
    private static final int THREAD_COUNT = 2;

    @Autowired
    DeliveryRegionService sut;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        // 이력이 우편번호를 외래키로 참조하므로 이력부터 지운다
        jdbcTemplate.update("DELETE FROM remote_area_postal_code_history WHERE postal_code = ?", TEST_POSTAL_CODE);
        jdbcTemplate.update("DELETE FROM remote_area_postal_code WHERE postal_code = ?", TEST_POSTAL_CODE);
    }

    @Test
    void 같은_새_번호를_동시에_등록하면_한_건만_성공하고_이력도_한_건이다() throws Exception {
        // when
        List<String> results = runTwice(() -> sut.register(command(), ADMIN_ID));

        // then
        assertSoftly(softly -> {
            softly.assertThat(results).containsExactlyInAnyOrder("SUCCESS", DeliveryErrorCode.POSTAL_REGION_DUPLICATED.name());
            softly.assertThat(historyCount("REGISTERED")).isEqualTo(1);
        });
    }

    @Test
    void 같은_번호를_동시에_삭제하면_한_건만_성공하고_이력도_한_건이다() throws Exception {
        // given
        sut.register(command(), ADMIN_ID);

        // when
        List<String> results = runTwice(() -> sut.delete(TEST_POSTAL_CODE, ADMIN_ID));

        // then
        assertSoftly(softly -> {
            softly.assertThat(results).containsExactlyInAnyOrder("SUCCESS", DeliveryErrorCode.POSTAL_REGION_NOT_FOUND.name());
            softly.assertThat(historyCount("DELETED")).isEqualTo(1);
        });
    }

    private static PostalRegionRegisterCommand command() {
        return new PostalRegionRegisterCommand(TEST_POSTAL_CODE, "JEJU_ISLANDS", 2);
    }

    private Integer historyCount(String action) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remote_area_postal_code_history WHERE postal_code = ? AND action = ?",
                Integer.class, TEST_POSTAL_CODE, action);
    }

    // 두 스레드가 동시에 같은 작업을 시작하게 하고, 각 결과를 성공 또는 에러 코드 이름으로 돌려준다
    private static List<String> runTwice(Runnable task) {
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> request = () -> {
            start.await();
            try {
                task.run();
                return "SUCCESS";
            } catch (RestApiException e) {
                return e.getErrorCode().name();
            }
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT)) {
            List<Future<String>> futures = List.of(executor.submit(request), executor.submit(request));
            start.countDown();
            return futures.stream().map(DeliveryRegionServiceConcurrencyIntegrationTest::await).toList();
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
