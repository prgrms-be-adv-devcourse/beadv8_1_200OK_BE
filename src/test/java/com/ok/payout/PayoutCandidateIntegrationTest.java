package com.ok.payout;

import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.repository.PayoutCandidateItemRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 실제 Spring 컨텍스트 + MySQL 컨테이너에서 이벤트 발행 → 리스너 → 저장까지 확인
// @ApplicationModuleListener는 커밋 후 별도 스레드(비동기)에서 실행되므로, 결과는 await()로 기다린 뒤 확인한다
// 테스트마다 겹치지 않는 주문항목 ID를 쓰고, 정리할 때도 그 ID의 데이터만 지운다 (병렬 실행 시 간섭 방지)
class PayoutCandidateIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // 이 클래스 전용 주문항목 ID 번호대 (다른 테스트 클래스와 겹치지 않게 큰 수에서 시작)
    // AtomicLong: 여러 스레드가 동시에 불러도 같은 번호가 나오지 않음
    private static final AtomicLong ORDER_ITEM_ID_SEQ = new AtomicLong(9_000_000L);

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PayoutCandidateItemRepository repository;

    // 이 테스트에서 사용한 주문항목 ID (정리할 때 이 ID만 지움)
    private final List<Long> usedOrderItemIds = new ArrayList<>();

    // 이 테스트가 만든 데이터만 정리
    // (리포지토리에는 삭제 기능이 없으므로 테스트 정리용으로만 SQL 직접 실행)
    @AfterEach
    void tearDown() {
        usedOrderItemIds.forEach(id ->
                jdbcTemplate.update("DELETE FROM payout_candidate_items WHERE order_item_id = ?", id));
        usedOrderItemIds.clear();
    }

    @Test
    @DisplayName("구매확정 이벤트를 발행하면 정산후보가 생성된다")
    void createsPayoutCandidates_whenPurchaseConfirmedEventPublished() {
        // Arrange
        Long orderItemId = nextOrderItemId();
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(orderItemId, 10L, 10_000L, 3_000L);

        // Act
        // 주문 쪽처럼 트랜잭션 안에서 발행 (커밋되어야 리스너가 실행됨)
        publishInTransaction(event);

        // Assert: 리스너가 저장을 끝낼 때까지 최대 5초 동안 반복 확인
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(repository.findAllByOrderItemId(orderItemId))
                        .extracting(PayoutCandidateItem::getEventType, PayoutCandidateItem::getAmount)
                        .containsExactlyInAnyOrder(
                                tuple(PayoutEventType.SALE_AMOUNT, 9_700L),
                                tuple(PayoutEventType.SALE_FEE, 300L),
                                tuple(PayoutEventType.SALE_SHIPPING_FEE, 3_000L)
                        ));
    }

    @Test
    @DisplayName("같은 구매확정 이벤트를 두 번 받아도 정산후보 수는 그대로고 실패로 남지 않는다")
    void isIdempotent_whenSameEventPublishedTwice() {
        // Arrange: 첫 번째 이벤트로 정산 후보 3건 생성
        Long orderItemId = nextOrderItemId();
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(orderItemId, 10L, 10_000L, 3_000L);
        publishInTransaction(event);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(repository.findAllByOrderItemId(orderItemId)).hasSize(3));

        // Act: 같은 이벤트를 한 번 더 발행 (재발행 상황)
        publishInTransaction(event);

        // Assert
        // 1) 두 번째 처리까지 완료(COMPLETED)될 때까지 기다림 → 리스너가 실제로 두 번 실행됐다는 증거
        await().atMost(TIMEOUT).until(() -> countPublications(orderItemId, "COMPLETED") == 2);
        // 2) 정산 후보는 그대로 3건
        assertThat(repository.findAllByOrderItemId(orderItemId)).hasSize(3);
        // 3) 중복이 실패(FAILED)로 남지 않음 (리뷰 지적 사항)
        assertThat(countPublications(orderItemId, "FAILED")).isZero();
    }

    //이벤트테이블에 실패로 기록된다.
    @Test
    @DisplayName("잘못된 값의 이벤트는 정산후보를 만들지 않고 실패로 기록된다")
    void doesNotCreateCandidatesAndRecordsFailure_whenEventHasInvalidValue() {
        // Arrange
        Long orderItemId = nextOrderItemId();
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(orderItemId, 10L, -1L, 3_000L);

        // Act
        publishInTransaction(event);

        // Assert
        // 1) 리스너가 실제로 실행되어 실패했는지: event_publication에 FAILED로 남을 때까지 기다림
        await().atMost(TIMEOUT).until(() -> countPublications(orderItemId, "FAILED") == 1);
        // 2) 이 주문항목의 정산 후보는 저장되지 않음
        assertThat(repository.findAllByOrderItemId(orderItemId)).isEmpty();
    }

    // 겹치지 않는 주문항목 ID를 만들고, 정리 대상으로 기록
    private Long nextOrderItemId() {
        Long id = ORDER_ITEM_ID_SEQ.incrementAndGet();
        usedOrderItemIds.add(id);
        return id;
    }

    // 트랜잭션 안에서 이벤트 발행 (커밋 후 리스너 실행)
    private void publishInTransaction(OrderProductConfirmedEvent event) {
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    // event_publication에서 해당 주문항목 이벤트 중 특정 상태(COMPLETED, FAILED 등)인 건수
    // JSON_EXTRACT로 orderItemId 값을 직접 꺼내 비교 → JSON 필드 순서나 공백이 바뀌어도 영향 없음
    private int countPublications(Long orderItemId, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication " +
                        "WHERE status = ? AND JSON_EXTRACT(serialized_event, '$.orderItemId') = ?",
                Integer.class,
                status, orderItemId);
        return count == null ? 0 : count;
    }
}
