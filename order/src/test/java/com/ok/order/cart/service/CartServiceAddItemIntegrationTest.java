package com.ok.order.cart.service;

import com.ok.order.OrderSchema;
import com.ok.order.cart.service.dto.AddCartItemResult;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 장바구니 담기의 "없으면 넣고, 있으면 수량 더하기"(insertOrAddQuantity)를 실제 DB로 확인한다.
// 동시 담기는 같은 옵션을 여러 스레드가 한 번에 담아, 확인 후 저장이나 읽고 더해서 저장하면 생기는 유니크 위반·수량 누락이 없는지 본다.
class CartServiceAddItemIntegrationTest extends AbstractIntegrationTest {

    // 수량 상한(99)에 걸리지 않으면서 커넥션 풀(10)보다 많아 요청이 겹치게 한다
    private static final int THREAD_COUNT = 20;
    private static final Long MEMBER_ID = 9_357L;
    private static final Long OPTION_ID = 777L;

    @Autowired
    CartService sut;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        sut.createCart(MEMBER_ID);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE ci FROM " + OrderSchema.NAME + ".cart_item ci JOIN " + OrderSchema.NAME
                + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".cart WHERE member_id = ?", MEMBER_ID);
    }

    @Test
    void 선택_해제한_옵션을_다시_담으면_한_줄에_수량이_더해지고_다시_선택된다() {
        // given
        AddCartItemResult first = sut.addItem(MEMBER_ID, OPTION_ID, 3);
        sut.changeSelected(MEMBER_ID, first.cartItemId(), false);

        // when
        AddCartItemResult second = sut.addItem(MEMBER_ID, OPTION_ID, 2);

        // then
        assertSoftly(softly -> {
            softly.assertThat(first.merged()).isFalse();
            softly.assertThat(second.merged()).isTrue();
            softly.assertThat(second.cartItemId()).isEqualTo(first.cartItemId());
            softly.assertThat(second.quantity()).isEqualTo(5);
            softly.assertThat(rowCount()).isEqualTo(1);
            softly.assertThat(selected()).isTrue();
        });
    }

    @Test
    void 더한_수량이_99개를_넘으면_99개에서_멈춘다() {
        // given
        sut.addItem(MEMBER_ID, OPTION_ID, 98);

        // when
        AddCartItemResult result = sut.addItem(MEMBER_ID, OPTION_ID, 5);

        // then
        assertThat(result.quantity()).isEqualTo(99);
    }

    @Test
    void 같은_옵션을_동시에_처음_담아도_모두_성공하고_수량이_모두_반영된다() throws Exception {
        // when
        List<String> results = concurrently(() -> sut.addItem(MEMBER_ID, OPTION_ID, 1));

        // then
        assertSoftly(softly -> {
            softly.assertThat(results).containsOnly("SUCCESS");
            softly.assertThat(rowCount()).isEqualTo(1);
            softly.assertThat(quantity()).isEqualTo(THREAD_COUNT);
        });
    }

    @Test
    void 이미_담긴_옵션을_동시에_다시_담아도_수량이_모두_반영된다() throws Exception {
        // given
        sut.addItem(MEMBER_ID, OPTION_ID, 1);

        // when
        List<String> results = concurrently(() -> sut.addItem(MEMBER_ID, OPTION_ID, 1));

        // then
        assertSoftly(softly -> {
            softly.assertThat(results).containsOnly("SUCCESS");
            softly.assertThat(rowCount()).isEqualTo(1);
            softly.assertThat(quantity()).isEqualTo(1 + THREAD_COUNT);
        });
    }

    // 리스너처럼 이미 열린 트랜잭션에 합류해도 결과가 같은지 본다. 격리 수준·스냅샷에 기대면 여기서 깨진다(#362)
    @Test
    void 바깥_트랜잭션_안에서_같은_옵션을_동시에_처음_담아도_수량이_모두_반영된다() throws Exception {
        // when
        List<String> results = concurrently(() -> transactionTemplate.executeWithoutResult(
                status -> sut.addItem(MEMBER_ID, OPTION_ID, 1)));

        // then
        assertSoftly(softly -> {
            softly.assertThat(results).containsOnly("SUCCESS");
            softly.assertThat(rowCount()).isEqualTo(1);
            softly.assertThat(quantity()).isEqualTo(THREAD_COUNT);
        });
    }

    private List<String> concurrently(Runnable request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> task = () -> {
            start.await();
            try {
                request.run();
                return "SUCCESS";
            } catch (RuntimeException e) {
                return e.getClass().getSimpleName();
            }
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < THREAD_COUNT; i++) {
                futures.add(executor.submit(task));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private Integer rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + OrderSchema.NAME + ".cart_item ci JOIN "
                + OrderSchema.NAME + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?", Integer.class, MEMBER_ID);
    }

    private Integer quantity() {
        return jdbcTemplate.queryForObject("SELECT COALESCE(SUM(ci.quantity), 0) FROM " + OrderSchema.NAME
                + ".cart_item ci JOIN " + OrderSchema.NAME + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?",
                Integer.class, MEMBER_ID);
    }

    private Boolean selected() {
        return jdbcTemplate.queryForObject("SELECT ci.selected FROM " + OrderSchema.NAME + ".cart_item ci JOIN "
                + OrderSchema.NAME + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?", Boolean.class, MEMBER_ID);
    }
}
