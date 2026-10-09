package com.ok.payout;

import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.common.exception.RestApiException;
import com.ok.payout.Service.PayoutCandidateService;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutErrorCode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

// 통합 테스트: 실제 Spring 컨텍스트 + MySQL 컨테이너에서 이벤트 발행 → 리스너 → 저장까지 확인
// @ApplicationModuleListener는 커밋 후 별도 스레드(비동기)에서 실행되므로, 결과는 await()로 기다린 뒤 확인한다
// 다른 테스트가 남긴 데이터가 있을 수 있으므로, 검증은 테스트마다 고유한 주문항목 ID로 조회해서 한다
class PayoutCandidateIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PayoutCandidateService service;

    @Autowired
    private PayoutCandidateItemRepository repository;

    // 이 클래스가 만든 데이터가 다른 테스트에 영향을 주지 않도록 정리
    // (리포지토리에는 삭제 기능이 없으므로 테스트 정리용으로만 SQL 직접 실행)
    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM payout_candidate_items");
    }

    @Test
    @DisplayName("구매확정 이벤트를 발행하면 정산후보가 생성된다")
    void createsPayoutCandidates_whenPurchaseConfirmedEventPublished() {
        // Arrange
        Long orderItemId = nextOrderItemId();
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(orderItemId, 10L, 10_000L, 3_000L);

        // Act
        // 주문 쪽처럼 트랜잭션 안에서 발행 (커밋되어야 리스너가 실행됨)
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

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
    @DisplayName("같은 구매확정을 두 번 처리해도 정산후보 수는 그대로다")
    void candidateCountUnchanged_whenSamePurchaseConfirmedProcessedTwice() {
        // Arrange
        Long orderItemId = nextOrderItemId();
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(orderItemId, 10L, 10_000L, 3_000L);
        service.createFromPurchaseConfirmed(event);

        // Act & Assert
        assertThatThrownBy(() -> service.createFromPurchaseConfirmed(event))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_CANDIDATE_ALREADY_EXISTS));
        assertThat(repository.findAllByOrderItemId(orderItemId)).hasSize(3);
    }

    //이벤트테이블에 실패로 기록된다.
    @Test
    @DisplayName("잘못된 값의 이벤트는 정산후보를 만들지 않고 실패로 기록된다")
    void doesNotCreateCandidatesAndRecordsFailure_whenEventHasInvalidValue() {
        // Arrange
        Long orderItemId = nextOrderItemId();
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(orderItemId, 10L, -1L, 3_000L);

        // Act
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        // Assert
        // 1) 리스너가 실제로 실행되어 실패했는지: event_publication에 FAILED로 남을 때까지 기다림
        await().atMost(TIMEOUT).until(() -> countFailedPublications(orderItemId) == 1);
        // 2) 이 주문항목의 정산 후보는 저장되지 않음
        assertThat(repository.findAllByOrderItemId(orderItemId)).isEmpty();
    }

    // 정산후보 테이블에 저장된 주문항목 번호 중 가장 큰 값의 다음 번호. 없으면 100
    private Long nextOrderItemId() {
        Long maxId = jdbcTemplate.queryForObject(
                "SELECT MAX(order_item_id) FROM payout_candidate_items",
                Long.class);
        return maxId == null ? 100L : maxId + 1;
    }

    // event_publication에서 해당 주문항목 이벤트 중 실패(FAILED) 상태인 건수
    // 끝에 쉼표를 붙여서 300이 3001, 30042 같은 번호와 섞이지 않게 함
    private int countFailedPublications(Long orderItemId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE status = 'FAILED' AND serialized_event LIKE ?",
                Integer.class,
                "%\"orderItemId\":" + orderItemId + ",%");
        return count == null ? 0 : count;
    }
}