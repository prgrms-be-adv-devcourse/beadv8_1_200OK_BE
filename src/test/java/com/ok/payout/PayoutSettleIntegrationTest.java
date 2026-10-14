package com.ok.payout;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutItem;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.repository.PayoutCandidateItemRepository;
import com.ok.payout.repository.PayoutItemRepository;
import com.ok.payout.repository.PayoutQueryRepository;
import com.ok.payout.service.PayoutJobRunner;
import com.ok.payout.service.PayoutService;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import org.assertj.core.groups.Tuple;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// 통합 테스트: 실제 Spring 컨텍스트 + MySQL 컨테이너에서 정산 배치(정산 후보 → 정산 생성) 확인
// - 정산 후보는 created_at을 과거로 넣어야 하므로 리포지토리 대신 SQL로 직접 넣는다 (저장 시 Auditing이 현재 시각으로 덮어쓰기 때문)
// - 정산(payout, payout_item)은 이 클래스만 만들고 "마지막 정산일"이 범위 계산에 쓰이므로, 테스트 전후로 전부 비운다
class PayoutSettleIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 주문항목 ID 번호대 (PayoutCandidateIntegrationTest는 9_000_000부터 사용)
    private static final long ORDER_ITEM_ID_START = 8_000_000L;
    private static final long ORDER_ITEM_ID_END = 9_000_000L;
    private static final AtomicLong ORDER_ITEM_ID_SEQ = new AtomicLong(ORDER_ITEM_ID_START);

    // 서비스 단위 테스트에서 쓰는 고정 기간 [09/21, 09/25)
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 21, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 9, 25, 0, 0);
    private static final int LIMIT = PayoutPolicy.PAYOUT_BATCH_PAYEE_SIZE;

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private PayoutQueryRepository payoutQueryRepository;

    @Autowired
    private PayoutJobRunner payoutJobRunner;

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private Job payoutJob;

    @Autowired
    private PayoutCandidateItemRepository candidateRepository;

    @Autowired
    private PayoutItemRepository payoutItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Hibernate가 DB와 시간을 주고받는 기준 시간대 (application.yaml: hibernate.jdbc.time_zone, 기본 UTC)
    @Value("${spring.jpa.properties.hibernate.jdbc.time_zone}")
    private String dbTimeZone;

    // 이전 테스트가 중간에 실패해 남긴 데이터가 있어도 영향받지 않도록 시작 전에도 정리
    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ---------- settle() : 기간 조건 / 중복 방지 / limit ----------

    @Test
    @DisplayName("기간 안의 후보만 정산된다: from 이전, to 이후(15일 안 지남, 테스트에서는 기간을 9/21~9/25로 임의 지정) 후보는 제외")
    void settlesOnlyCandidatesWithinPeriod() {
        // Arrange: 판매자 10의 후보를 기간 경계 앞뒤로 넣음
        insertSaleAmount(10L, 1_000L, FROM.minusSeconds(1));   // from 직전 → 제외 (이미 지난 범위)
        insertSaleAmount(10L, 2_000L, FROM);                   // from 정각 → 포함 (>=)
        insertSaleAmount(10L, 4_000L, TO.minusSeconds(1));     // to 직전 → 포함
        insertSaleAmount(10L, 8_000L, TO);                     // to 정각 → 제외 (<, 아직 15일 안 지남)

        // Act
        int settled = payoutService.settle(FROM, TO, LIMIT);

        // Assert: 포함된 2,000 + 4,000만 합산
        assertThat(settled).isEqualTo(1);
        assertThat(findPayouts()).containsExactly(tuple(10L, false, 6_000L));
    }

    @Test
    @DisplayName("from이 null(첫 실행)이면 하한 없이 to 이전 후보를 모두 정산한다")
    void settlesAllBeforeTo_whenFromIsNull() {
        // Arrange
        insertSaleAmount(10L, 1_000L, FROM.minusYears(1));   // 아주 오래된 후보
        insertSaleAmount(10L, 2_000L, TO.minusDays(1));

        // Act
        payoutService.settle(null, TO, LIMIT);

        // Assert
        assertThat(findPayouts()).containsExactly(tuple(10L, false, 3_000L));
    }

    @Test
    @DisplayName("이미 정산된 후보는 다시 정산되지 않는다: 두 번째 settle은 0을 반환하고 정산 수가 그대로")
    void doesNotSettleTwice() {
        // Arrange: 판매자 10 주문상품 1건 (대금·수수료·배송비 3종)
        long orderItemId = nextOrderItemId();
        LocalDateTime createdAt = TO.minusDays(1);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemId, 10L, 9_700L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderItemId, 10L, 300L, createdAt);
        insertCandidate(PayoutEventType.SALE_SHIPPING_FEE, orderItemId, 10L, 3_000L, createdAt);
        payoutService.settle(FROM, TO, LIMIT);   // 1차 정산

        // Act: 같은 기간으로 한 번 더
        int settledAgain = payoutService.settle(FROM, TO, LIMIT);

        // Assert
        assertThat(settledAgain).isZero();                         // NOT EXISTS로 모두 걸러짐
        assertThat(countRows("payout")).isEqualTo(2);              // 판매자 10 + 시스템, 늘지 않음
        assertThat(countRows("payout_item")).isEqualTo(3);         // 후보 3건 = 정산 항목 3건
        assertThat(findPayouts()).containsExactlyInAnyOrder(
                tuple(10L, false, 12_700L),                        // 대금 + 배송비
                tuple(null, true, 300L));                          // 수수료 (시스템 몫 → 수취인 없음)
        // 정산 항목은 각 정산후보와 1:1로 연결된다
        assertPayoutItemsMatchCandidates(orderItemId);
    }

    @Test
    @DisplayName("같은 주문상품·같은 타입의 수동 조정 2건은 원래 후보와 함께 각각 한 번씩 정산된다")
    void settlesEachManualAdjustmentOnce() {
        // Arrange: 원래 판매 대금 + 같은 주문상품·같은 타입의 조정 2건 (차감 -500, 보정 +200)
        long orderItemId = nextOrderItemId();
        LocalDateTime createdAt = TO.minusDays(1);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemId, 10L, 9_700L, createdAt);
        insertAdjustment(PayoutEventType.SALE_AMOUNT, orderItemId, 10L, -500L, 1L, createdAt);
        insertAdjustment(PayoutEventType.SALE_AMOUNT, orderItemId, 10L, 200L, 2L, createdAt);

        // Act
        payoutService.settle(FROM, TO, LIMIT);

        // Assert: 판매자 10 정산 1건, 금액 = 9,700 - 500 + 200
        assertThat(findPayouts()).containsExactly(tuple(10L, false, 9_400L));
        // 후보 3건이 각각 정산 항목 1건씩 (candidate_id 중복 없음)
        assertPayoutItemsMatchCandidates(orderItemId);
    }

    @Test
    @DisplayName("원래 후보가 정산된 뒤 생긴 같은 주문상품·같은 타입의 조정 후보도 다음 배치에서 정산된다")
    void settlesAdjustmentCreatedAfterOriginalSettled() {
        // Arrange: 원래 판매 대금 후보를 먼저 정산
        long orderItemId = nextOrderItemId();
        LocalDateTime createdAt = TO.minusDays(1);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemId, 10L, 9_700L, createdAt);
        payoutService.settle(FROM, TO, LIMIT);   // 1차 정산 (원래 후보)

        // 그 뒤 같은 주문상품·같은 타입으로 조정 후보가 들어옴
        insertAdjustment(PayoutEventType.SALE_AMOUNT, orderItemId, 10L, 200L, 1L, createdAt);

        // Act: 다음 배치
        int settled = payoutService.settle(FROM, TO, LIMIT);

        // Assert: 조정 후보가 미정산으로 잡혀 판매자 10이 한 번 더 정산됨
        assertThat(settled).isEqualTo(1);
        assertThat(findPayouts()).containsExactlyInAnyOrder(
                tuple(10L, false, 9_700L),   // 1차: 원래 후보
                tuple(10L, false, 200L));    // 2차: 조정 후보
        assertPayoutItemsMatchCandidates(orderItemId);
    }

    @Test
    @DisplayName("판매자가 limit보다 많으면 limit명만 정산하고, 다음 호출에서 나머지를 정산한다")
    void settlesUpToLimit_thenRestOnNextCall() {
        // Arrange: 판매자 3명
        insertSaleAmount(10L, 1_000L, TO.minusDays(1));
        insertSaleAmount(20L, 1_000L, TO.minusDays(1));
        insertSaleAmount(30L, 1_000L, TO.minusDays(1));

        // Act & Assert
        assertThat(payoutService.settle(FROM, TO, 2)).isEqualTo(2);   // 1회차: 2명
        assertThat(payoutService.settle(FROM, TO, 2)).isEqualTo(1);   // 2회차: 남은 1명
        assertThat(payoutService.settle(FROM, TO, 2)).isZero();       // 3회차: 없음 → 종료 신호
        // 3회에 걸쳐 판매자 3명 모두 정산됨 (수취인 ID, 시스템 몫 여부, 금액). 대금만 넣었으므로 시스템(수수료) 정산은 없음
        assertThat(findPayouts()).containsExactlyInAnyOrder(
                tuple(10L, false, 1_000L),
                tuple(20L, false, 1_000L),
                tuple(30L, false, 1_000L));
    }

    // ---------- PayoutQueryRepository ----------

    @Test
    @DisplayName("판매자 조회는 가장 먼저 들어온 후보의 시간순이다")
    void findUnsettledSellerIds_ordersByEarliestCandidate() {
        // Arrange: 판매자 ID 순서와 후보 시간 순서를 일부러 반대로
        insertSaleAmount(20L, 1_000L, TO.minusDays(3));   // 20이 가장 먼저
        insertSaleAmount(30L, 1_000L, TO.minusDays(2));
        insertSaleAmount(10L, 1_000L, TO.minusDays(1));   // 10이 가장 늦게
        insertSaleAmount(30L, 1_000L, TO.minusDays(4));   // 30에 더 이른 후보 추가 → 30이 가장 먼저가 됨

        // Act
        List<Long> sellerIds = payoutQueryRepository.findUnsettledSellerIds(FROM, TO, LIMIT);

        // Assert: 각 판매자의 MIN(created_at) 순 → 30(4일 전), 20(3일 전), 10(1일 전)
        assertThat(sellerIds).containsExactly(30L, 20L, 10L);
    }

    // ---------- 배치 Job 실행 ----------

    @Test
    @DisplayName("판매자가 한 번 처리 수(100명)보다 많아도 반복해서 전원 정산되고 Job이 COMPLETED")
    void job_settlesAllSellers_overMultipleIterations() throws Exception {
        // Arrange: 판매자 150명, 각 1건씩 (생성 20일 전 → 15일 지남). 첫 실행이라 from = null
        int sellerCount = LIMIT + 50;//150명
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        for (long sellerId = 1_001L; sellerId < 1_001L + sellerCount; sellerId++) {
            long orderItemId = nextOrderItemId();
            insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemId, sellerId, 9_700L, createdAt);
            insertCandidate(PayoutEventType.SALE_FEE, orderItemId, sellerId, 300L, createdAt);
        }

        // Act: 스케줄러/API와 같은 경로로 실행
        JobExecution execution = payoutJobRunner.run();

        // Assert
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(countPayouts("is_system = ?", false)).isEqualTo(sellerCount); // 판매자 150명 전원
        assertThat(countPayouts("is_system = ?", true)).isEqualTo(2);            // 100명 + 50명 → 회차마다 시스템 1건
        assertThat(execution.getStepExecutions())                                                        // 실행 기록의 write count = 정산한 판매자 수
                .singleElement()
                .satisfies(step -> assertThat(step.getWriteCount()).isEqualTo(sellerCount));
    }

    @Test
    @DisplayName("정산 대상이 없어도 Job은 COMPLETED로 끝난다")
    void job_completes_whenNothingToSettle() throws Exception {
        // Act
        JobExecution execution = payoutJobRunner.run();

        // Assert
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(countRows("payout")).isZero();
    }

    @Test
    @DisplayName("requestedAt 파라미터 없이 실행하면 Job이 FAILED (앱 시작 시 자동 실행 등 의도치 않은 실행 방지)")
    void job_fails_whenRequestedAtMissing() throws Exception {
        // Arrange: 정산 대상이 있어도
        insertSaleAmount(10L, 1_000L, LocalDateTime.now().minusDays(20));

        // Act: 파라미터 없이 직접 실행
        JobExecution execution = jobOperator.start(payoutJob, new JobParameters());

        // Assert: 실패하고, 정산은 하나도 만들어지지 않음
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(countRows("payout")).isZero();
    }

    @Test
    @DisplayName("Job 실행 기록이 DB(BATCH_JOB_EXECUTION)에 저장된다 (batch-jdbc 적용 확인)")
    void job_savesExecutionToDb() throws Exception {
        // Act: 정산 대상 없이 실행 (대상 유무와 상관없이 실행 기록은 남아야 함)
        JobExecution execution = payoutJobRunner.run();

        // Assert: 반환된 실행 ID로 메타 테이블을 직접 조회 → 메모리 저장이면 행이 없어 실패
        String status = jdbcTemplate.queryForObject(
                "SELECT STATUS FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?",
                String.class, execution.getId());
        assertThat(status).isEqualTo(BatchStatus.COMPLETED.name());
    }

    // ---------- 도우미 ----------

    // 판매 대금 후보 1건 (주문항목 ID는 자동 발급)
    private void insertSaleAmount(Long sellerId, Long amount, LocalDateTime createdAt) {
        insertCandidate(PayoutEventType.SALE_AMOUNT, nextOrderItemId(), sellerId, amount, createdAt);
    }

    // 정산 후보를 원하는 created_at으로 직접 저장
    private void insertCandidate(PayoutEventType eventType, long orderItemId, Long sellerId, Long amount,
                                 LocalDateTime createdAt) {
        LocalDateTime dbCreatedAt = toDbTime(createdAt);
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_item_id, seller_id, amount, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                eventType.name(), orderItemId, sellerId, amount, dbCreatedAt, dbCreatedAt);
    }

    // 관리자 수동 조정 후보를 원하는 created_at으로 직접 저장 (관리자 ID·사유는 테스트용 고정값)
    private void insertAdjustment(PayoutEventType eventType, long orderItemId, Long sellerId, Long amount,
                                  long adjustmentId, LocalDateTime createdAt) {
        LocalDateTime dbCreatedAt = toDbTime(createdAt);
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items " +
                        "(event_type, order_item_id, seller_id, amount, adjustment_id, admin_id, reason, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventType.name(), orderItemId, sellerId, amount, adjustmentId, 99L, "테스트 조정", dbCreatedAt, dbCreatedAt);
    }

    // Hibernate는 LocalDateTime을 DB에 보낼 때 JVM 시간대 → hibernate.jdbc.time_zone(UTC)으로 변환한다
    // jdbcTemplate은 이 변환을 거치지 않으므로, 직접 넣을 때도 같은 기준으로 바꿔야 QueryDSL 조회와 비교가 맞는다
    // (안 바꾸면 한국 PC에서 9시간 차이가 나서 기간 경계 테스트가 실패함)
    private LocalDateTime toDbTime(LocalDateTime jvmTime) {
        return jvmTime.atZone(ZoneId.systemDefault())        // 이 PC(JVM) 시간대 기준 시각으로 보고
                .withZoneSameInstant(ZoneId.of(dbTimeZone))  // 같은 순간을 DB 기준 시간대로 바꾼 뒤
                .toLocalDateTime();                          // 시간대 정보를 떼서 저장용 값으로
    }

    private long nextOrderItemId() {
        return ORDER_ITEM_ID_SEQ.incrementAndGet();
    }

    // 생성된 정산 목록 (수취인 ID, 시스템 몫 여부, 금액). 시스템 몫은 수취인 ID가 null
    // jdbcTemplate.query(SQL, RowMapper): 조회 결과를 한 행씩 RowMapper에 넘겨 원하는 객체로 바꾼다
    // rs = 현재 행, rowNum = 행 번호(여기선 안 씀). 행마다 tuple(수취인 ID, 시스템 몫 여부, 금액)을 만들어 List로 반환
    // → assertThat(findPayouts()).containsExactly(tuple(10L, false, 6_000L)) 처럼 바로 비교 가능
    // payee_id는 getObject로 읽음 (getLong은 NULL을 0으로 바꿔버려 시스템 몫과 구분이 안 됨)
    private List<Tuple> findPayouts() {
        return jdbcTemplate.query("SELECT payee_id, is_system, amount FROM payout",
                (rs, rowNum) -> tuple(
                        rs.getObject("payee_id", Long.class),
                        rs.getBoolean("is_system"),
                        rs.getLong("amount")));
    }

    private int countPayouts(String condition, Object arg) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payout WHERE " + condition, Integer.class, arg);
        return count == null ? 0 : count;
    }

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    // 주문항목의 정산후보와 정산 항목이 1:1로 연결됐는지 확인 (candidate_id가 후보 id와 정확히 일치, 중복 없음)
    private void assertPayoutItemsMatchCandidates(long orderItemId) {
        // 이 주문항목의 정산후보 id 목록
        List<Long> candidateIds = new ArrayList<>();
        for (PayoutCandidateItem candidate : candidateRepository.findAllByOrderItemId(orderItemId)) {
            candidateIds.add(candidate.getId());
        }

        // 정산 항목에 저장된 candidate_id 목록
        List<Long> payoutItemCandidateIds = new ArrayList<>();
        for (PayoutItem payoutItem : payoutItemRepository.findAll()) {
            payoutItemCandidateIds.add(payoutItem.getCandidateId());
        }

        // 두 목록이 순서 상관없이 개수·값까지 정확히 같아야 통과
        assertThat(payoutItemCandidateIds).containsExactlyInAnyOrderElementsOf(candidateIds);
    }

    // 정산은 전부, 정산 후보는 이 클래스 번호대만 지움 (자식 테이블 payout_item 먼저)
    // (리포지토리에는 삭제 기능이 없으므로 테스트 정리용으로만 SQL 직접 실행)
    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM payout_item");
        jdbcTemplate.update("DELETE FROM payout");
        jdbcTemplate.update("DELETE FROM payout_candidate_items WHERE order_item_id > ? AND order_item_id <= ?",
                ORDER_ITEM_ID_START, ORDER_ITEM_ID_END);
    }
}
