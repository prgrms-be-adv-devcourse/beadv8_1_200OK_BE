package com.ok.order.cart.service;

import com.ok.order.OrderSchema;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 장바구니가 없는 회원의 장바구니 생성을 회원가입 리스너 경로와 API 경로에서 동시에 보내 uk_cart_member 경합을 만든다.
// 경합 여부와 상관없이 결과(모두 성공, 행 1개)를 확인한다. 생성 분기 자체는 CartServiceTest가 확인한다.
class CartServiceConcurrencyIntegrationTest extends AbstractIntegrationTest {

    // 두 건만으로는 경합이 드물게 비켜 갈 수 있어 여러 건을 동시에 보낸다
    private static final int THREAD_COUNT = 6;
    private static final Long MEMBER_ID = 9_356L;

    @Autowired
    CartService sut;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".cart WHERE member_id = ?", MEMBER_ID);
    }

    @Test
    void 회원가입_리스너와_장바구니_조회가_동시에_장바구니를_만들어도_모두_성공하고_한_행만_남는다() {
        // given: 절반은 회원가입 리스너 경로(createCart), 절반은 API 경로(getMyCart)
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> requests = IntStream.range(0, THREAD_COUNT)
                .mapToObj(i -> (Callable<String>) () -> {
                    start.await();
                    try {
                        if (i % 2 == 0) {
                            sut.createCart(MEMBER_ID);
                        } else {
                            sut.getMyCart(MEMBER_ID);
                        }
                        return "SUCCESS";
                    } catch (RuntimeException e) {
                        return e.getClass().getSimpleName();
                    }
                })
                .toList();

        // when
        List<String> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT)) {
            List<Future<String>> futures = requests.stream().map(executor::submit).toList();
            start.countDown();
            results = futures.stream().map(CartServiceConcurrencyIntegrationTest::await).toList();
        }

        // then
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + OrderSchema.NAME + ".cart WHERE member_id = ?", Integer.class, MEMBER_ID);
        assertSoftly(softly -> {
            softly.assertThat(results).containsOnly("SUCCESS");
            softly.assertThat(rowCount).isEqualTo(1);
        });
    }

    @Test
    void 이미_장바구니가_있는_회원에게_다시_만들어도_예외_없이_한_행만_남는다() {
        // given
        sut.createCart(MEMBER_ID);

        // when
        sut.createCart(MEMBER_ID);
        sut.getMyCart(MEMBER_ID);

        // then
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + OrderSchema.NAME + ".cart WHERE member_id = ?", Integer.class, MEMBER_ID);
        assertThat(rowCount).isEqualTo(1);
    }

    private static String await(Future<String> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("동시 요청이 끝나지 않았습니다.", e);
        }
    }
}
