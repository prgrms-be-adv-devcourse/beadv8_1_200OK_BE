package com.ok.payout;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutItem;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.domain.PayoutSellerDraft;
import com.ok.payout.domain.PayoutSellerDraftItem;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.domain.PayoutSnapshotItem;
import com.ok.payout.domain.PayoutSystemDraftItem;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.payout.repository.PayoutCandidateItemRepository;
import com.ok.payout.repository.PayoutQueryRepository;
import com.ok.payout.repository.PayoutRepository;
import com.ok.payout.repository.PayoutSellerDraftRepository;
import com.ok.payout.repository.PayoutSnapshotItemRepository;
import com.ok.payout.repository.PayoutSnapshotRepository;
import com.ok.payout.repository.PayoutSystemDraftItemRepository;
import com.ok.payout.service.PayoutJobRunner;
import com.ok.payout.service.PayoutService;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 통합 테스트: 실제 Spring 컨텍스트 + MySQL 컨테이너에서 정산 배치(스냅샷 → draft → 검증 → 확정 → 발행) 확인
// - 정산후보는 created_at을 과거로 넣어야 하므로 리포지토리 대신 SQL로 직접 넣는다 (저장 시 Auditing이 현재 시각으로 덮어쓰기 때문)
// - 결과 확인은 JPA 리포지토리로 한다 (팀 규칙). JDBC는 데이터 준비·정리에만 쓴다
// - 정산·스냅샷·draft는 이 클래스만 만들고 "마지막 정산일"이 범위 계산에 쓰이므로, 테스트 전후로 전부 비운다
class PayoutSettleIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 주문항목 ID 번호대 (PayoutCandidateIntegrationTest는 9_000_000부터 사용)
    private static final long ORDER_ITEM_ID_START = 8_000_000L;
    private static final long ORDER_ITEM_ID_END = 9_000_000L;
    private static final AtomicLong ORDER_ITEM_ID_SEQ = new AtomicLong(ORDER_ITEM_ID_START);

    // 서비스를 직접 호출할 때 쓰는 가짜 Job 실행 ID (스냅샷 UK라 테스트마다 달라야 함, BATCH 테이블과 FK 없음)
    private static final AtomicLong JOB_EXECUTION_ID_SEQ = new AtomicLong(7_000_000L);

    // 서비스 단위로 호출할 때 쓰는 고정 기간 [09/21, 09/25)
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
    private PayoutSnapshotRepository snapshotRepository;

    @Autowired
    private PayoutSnapshotItemRepository snapshotItemRepository;

    @Autowired
    private PayoutSellerDraftRepository sellerDraftRepository;

    @Autowired
    private PayoutSystemDraftItemRepository systemDraftItemRepository;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Hibernate가 DB와 시간을 주고받는 기준 시간대 (application.yaml: hibernate.jdbc.time_zone)
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

    // ---------- snapshotStep ----------

    @Test
    void 스냅샷은_기간_안의_후보만_대상으로_기록한다() {
        // given: 판매자 10의 후보를 기간 경계 앞뒤로 넣음
        insertSaleAmount(10L, 1_000L, FROM.minusSeconds(1));   // from 직전 → 제외 (이미 지난 범위)
        insertSaleAmount(10L, 2_000L, FROM);                   // from 정각 → 포함 (>=)
        insertSaleAmount(10L, 4_000L, TO.minusSeconds(1));     // to 직전 → 포함
        insertSaleAmount(10L, 8_000L, TO);                     // to 정각 → 제외 (<, 아직 15일 안 지남)

        // when
        Long snapshotId = createSnapshot(FROM, TO);

        // then
        assertThat(snapshotItemRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutSnapshotItem::getAmount)
                .containsExactlyInAnyOrder(2_000L, 4_000L);
    }

    @Test
    void 하한이_null이면_상한_이전_후보를_모두_대상으로_기록한다() {
        // given: 첫 실행 (정산 이력 없음)
        insertSaleAmount(10L, 1_000L, FROM.minusYears(1));   // 아주 오래된 후보
        insertSaleAmount(10L, 2_000L, TO.minusDays(1));

        // when
        Long snapshotId = createSnapshot(null, TO);

        // then
        assertThat(snapshotItemRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutSnapshotItem::getAmount)
                .containsExactlyInAnyOrder(1_000L, 2_000L);
    }

    @Test
    void 이미_정산된_후보는_다음_스냅샷의_대상이_아니다() {
        // given: 한 번 끝까지 정산
        insertSaleAmount(10L, 1_000L, TO.minusDays(1));
        settleAll(FROM, TO);

        // when: 같은 기간으로 새 스냅샷
        Long snapshotId = createSnapshot(FROM, TO);

        // then: 정산 항목에 있는 후보는 빠짐
        assertThat(snapshotItemRepository.findBySnapshotId(snapshotId)).isEmpty();
    }

    @Test
    void 같은_Job_실행으로_스냅샷을_두_번_만들면_두_번째는_실패하고_1건만_남는다() {
        // given: Job 실행 1회 = 스냅샷 1건 (UK job_execution_id)
        insertSaleAmount(10L, 1_000L, TO.minusDays(1));
        long jobExecutionId = JOB_EXECUTION_ID_SEQ.incrementAndGet();
        payoutService.createSnapshot(jobExecutionId, FROM, TO);

        // when & then
        assertThatThrownBy(() -> payoutService.createSnapshot(jobExecutionId, FROM, TO))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(snapshotRepository.findAll())
                .filteredOn(snapshot -> snapshot.getJobExecutionId().equals(jobExecutionId))
                .hasSize(1);
    }

    // ---------- draftStep ----------

    @Test
    void 스냅샷을_만든_뒤에_생긴_후보는_이번_draft에_들어가지_않는다() {
        // given: 스냅샷을 만든 뒤 같은 기간의 후보가 새로 들어옴
        insertSaleAmount(10L, 1_000L, TO.minusDays(2));
        Long snapshotId = createSnapshot(FROM, TO);
        insertSaleAmount(10L, 5_000L, TO.minusDays(1));

        // when
        draftAll(snapshotId, LIMIT);

        // then: 스냅샷 시점의 후보(1,000)만 draft됨, 새 후보는 다음 배치로
        assertThat(sellerDraftRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutSellerDraft::getSellerId, PayoutSellerDraft::getAmount)
                .containsExactly(tuple(10L, 1_000L));
    }

    @Test
    void 판매자가_limit보다_많으면_회차마다_limit명씩_처리하고_마지막에_0을_반환한다() {
        // given: 판매자 3명, 한 회차에 2명
        insertSaleAmount(10L, 1_000L, TO.minusDays(1));
        insertSaleAmount(20L, 1_000L, TO.minusDays(1));
        insertSaleAmount(30L, 1_000L, TO.minusDays(1));
        Long snapshotId = createSnapshot(FROM, TO);

        // when & then: 1회차 2명 → 2회차 1명 → 3회차 없음(FINISHED 신호)
        assertThat(payoutService.draft(snapshotId, 2)).isEqualTo(2);
        assertThat(payoutService.draft(snapshotId, 2)).isEqualTo(1);
        assertThat(payoutService.draft(snapshotId, 2)).isZero();
        // 각 판매자는 draft에 한 번씩만
        assertThat(sellerDraftRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutSellerDraft::getSellerId)
                .containsExactlyInAnyOrder(10L, 20L, 30L);
    }

    @Test
    void 정산후보_하나는_판매자_draft와_시스템_draft_중_한_곳에만_들어간다() {
        // given: 판매자 10 주문상품 1건 (대금·수수료·배송비 3종)
        long orderItemId = nextOrderItemId();
        LocalDateTime createdAt = TO.minusDays(1);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemId, 10L, 9_700L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderItemId, 10L, 300L, createdAt);
        insertCandidate(PayoutEventType.SALE_SHIPPING_FEE, orderItemId, 10L, 3_000L, createdAt);
        Long snapshotId = createSnapshot(FROM, TO);

        // when
        draftAll(snapshotId, LIMIT);

        // then
        List<Long> sellerCandidateIds = new ArrayList<>();
        for (PayoutSellerDraft draft : sellerDraftRepository.findBySnapshotId(snapshotId)) {
            for (PayoutSellerDraftItem item : draft.getItems()) {
                sellerCandidateIds.add(item.getCandidateId());
            }
        }
        List<Long> systemCandidateIds = new ArrayList<>();
        for (PayoutSystemDraftItem item : systemDraftItemRepository.findBySnapshotId(snapshotId)) {
            systemCandidateIds.add(item.getCandidateId());
        }
        List<Long> allDraftedIds = new ArrayList<>(sellerCandidateIds);
        allDraftedIds.addAll(systemCandidateIds);

        assertSoftly(softly -> {
            softly.assertThat(sellerCandidateIds).doesNotContainAnyElementsOf(systemCandidateIds);   // 겹치지 않음
            softly.assertThat(allDraftedIds).containsExactlyInAnyOrderElementsOf(candidateIdsOf(orderItemId));   // 합치면 전부
            softly.assertThat(systemCandidateIds).hasSize(1);   // 수수료 1건만 시스템 쪽
        });
    }

    @Test
    void 판매자는_가장_먼저_들어온_후보_순으로_뽑힌다() {
        // given: 판매자 ID 순서와 후보 시간 순서를 일부러 반대로
        insertSaleAmount(20L, 1_000L, TO.minusDays(3));   // 20이 가장 먼저
        insertSaleAmount(30L, 1_000L, TO.minusDays(2));
        insertSaleAmount(10L, 1_000L, TO.minusDays(1));   // 10이 가장 늦게
        insertSaleAmount(30L, 1_000L, TO.minusDays(4));   // 30에 더 이른 후보 추가 → 30이 가장 먼저가 됨
        Long snapshotId = createSnapshot(FROM, TO);

        // when
        List<Long> sellerIds = payoutQueryRepository.findUndraftedSellerIds(snapshotId, LIMIT);

        // then: 각 판매자의 MIN(created_at) 순 → 30(4일 전), 20(3일 전), 10(1일 전)
        assertThat(sellerIds).containsExactly(30L, 20L, 10L);
    }

    // ---------- verifyStep ----------

    @Test
    void 검증에_실패하면_예외가_나고_정산은_만들어지지_않는다() {
        // given: draft까지 끝난 뒤, draft가 놓친 후보 1건이 스냅샷 목록에 있는 상황을 만듦
        insertSaleAmount(10L, 1_000L, TO.minusDays(1));
        Long snapshotId = createSnapshot(FROM, TO);
        draftAll(snapshotId, LIMIT);
        insertMissedSnapshotItem(snapshotId, 20L, 2_000L);

        // when & then
        assertThatThrownBy(() -> payoutService.verify(snapshotId))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.DRAFT_VERIFICATION_FAILED));
        assertThat(payoutRepository.count()).isZero();
    }

    // ---------- commitStep ----------

    @Test
    void 확정하면_판매자_몫_항목은_그_판매자_정산에_수수료_항목은_시스템_정산에_연결된다() {
        // given: 판매자 10, 20 각각 대금·수수료
        long orderItem10 = nextOrderItemId();
        long orderItem20 = nextOrderItemId();
        LocalDateTime createdAt = TO.minusDays(1);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItem10, 10L, 9_700L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderItem10, 10L, 300L, createdAt);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItem20, 20L, 19_400L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderItem20, 20L, 600L, createdAt);

        // when
        Long snapshotId = settleAll(FROM, TO);

        // then
        List<Payout> payouts = payoutRepository.findBySnapshotId(snapshotId);
        assertThat(payouts)
                .extracting(Payout::getPayeeId, Payout::isSystem, Payout::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(10L, false, 9_700L),
                        tuple(20L, false, 19_400L),
                        tuple(null, true, 900L));   // 수수료 300 + 600 (수취인 없음)
        for (Payout payout : payouts) {
            if (payout.isSystem()) {
                // 시스템 몫 정산의 항목은 모두 수수료
                assertThat(payout.getItems()).extracting(PayoutItem::getEventType).containsOnly(PayoutEventType.SALE_FEE);
            } else {
                // 판매자 몫 정산의 항목은 모두 그 판매자의 것이고 수수료가 없음
                assertThat(payout.getItems()).extracting(PayoutItem::getSellerId).containsOnly(payout.getPayeeId());
                assertThat(payout.getItems()).extracting(PayoutItem::getEventType).doesNotContain(PayoutEventType.SALE_FEE);
            }
        }
    }

    @Test
    void 판매자가_여러_회차로_나뉘어도_시스템_몫_정산은_스냅샷당_1건이다() {
        // given: 판매자 3명 각각 대금·수수료, 한 회차에 1명씩 → 3회차
        for (long sellerId = 10L; sellerId <= 30L; sellerId += 10L) {
            long orderItemId = nextOrderItemId();
            insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemId, sellerId, 9_700L, TO.minusDays(1));
            insertCandidate(PayoutEventType.SALE_FEE, orderItemId, sellerId, 300L, TO.minusDays(1));
        }
        Long snapshotId = createSnapshot(FROM, TO);
        draftAll(snapshotId, 1);
        payoutService.verify(snapshotId);

        // when
        payoutService.commit(snapshotId);

        // then: 시스템 몫은 3회차 수수료를 합쳐 1건
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .filteredOn(Payout::isSystem)
                .extracting(Payout::getAmount)
                .containsExactly(900L);
    }

    @Test
    void 수수료가_없는_스냅샷은_시스템_몫_정산을_만들지_않는다() {
        // given: 판매 대금만
        insertSaleAmount(10L, 1_000L, TO.minusDays(1));

        // when
        Long snapshotId = settleAll(FROM, TO);

        // then
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::isSystem)
                .containsExactly(tuple(10L, false));
    }

    @Test
    void 같은_스냅샷을_두_번_확정하면_두_번째는_실패하고_시스템_몫은_1건만_남는다() {
        // given: 수수료 후보만 있는 스냅샷 (판매자 몫 UK가 먼저 막지 않게, 시스템 몫만 남는 경우를 확인)
        // 시스템 몫은 payee_id = NULL이라 payout UK로 못 막고, payout_item UK(candidate_id) 위반 + 전체 롤백으로 막혀야 함
        insertCandidate(PayoutEventType.SALE_FEE, nextOrderItemId(), 10L, 300L, TO.minusDays(1));
        Long snapshotId = settleAll(FROM, TO);

        // when & then
        assertThatThrownBy(() -> payoutService.commit(snapshotId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::isSystem, Payout::getAmount)
                .containsExactly(tuple(null, true, 300L));
    }

    @Test
    void 같은_주문상품_같은_타입의_수동_조정_2건은_원래_후보와_함께_각각_한_번씩_정산된다() {
        // given: 원래 판매 대금 + 같은 주문상품·같은 타입의 조정 2건 (차감 -500, 보정 +200)
        long orderItemId = nextOrderItemId();
        LocalDateTime createdAt = TO.minusDays(1);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemId, 10L, 9_700L, createdAt);
        insertAdjustment(PayoutEventType.SALE_AMOUNT, orderItemId, 10L, -500L, 1L, createdAt);
        insertAdjustment(PayoutEventType.SALE_AMOUNT, orderItemId, 10L, 200L, 2L, createdAt);

        // when
        Long snapshotId = settleAll(FROM, TO);

        // then: 판매자 10 정산 1건, 금액 = 9,700 - 500 + 200, 후보 3건이 각각 정산 항목 1건씩
        List<Payout> payouts = payoutRepository.findBySnapshotId(snapshotId);
        assertThat(payouts).extracting(Payout::getPayeeId, Payout::getAmount).containsExactly(tuple(10L, 9_400L));
        assertThat(payouts.get(0).getItems())
                .extracting(PayoutItem::getCandidateId)
                .containsExactlyInAnyOrderElementsOf(candidateIdsOf(orderItemId));
    }

    @Test
    void 원래_후보가_정산된_뒤_생긴_조정_후보는_다음_배치에서_정산된다() {
        // given: 원래 판매 대금 후보를 먼저 정산
        long orderItemId = nextOrderItemId();
        LocalDateTime createdAt = TO.minusDays(1);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemId, 10L, 9_700L, createdAt);
        settleAll(FROM, TO);
        // 그 뒤 같은 주문상품·같은 타입으로 조정 후보가 들어옴
        insertAdjustment(PayoutEventType.SALE_AMOUNT, orderItemId, 10L, 200L, 1L, createdAt);

        // when: 다음 배치
        Long snapshotId = settleAll(FROM, TO);

        // then: 조정 후보만 새로 정산됨
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(10L, 200L));
    }

    // ---------- 배치 Job 실행 ----------

    @Test
    void 판매자가_한_번_처리_수보다_많아도_전원_정산되고_시스템_몫은_1건이다() throws Exception {
        // given: 판매자 150명, 각 대금·수수료 1건씩 (생성 20일 전 → 15일 지남). 첫 실행이라 from = null
        int sellerCount = LIMIT + 50;
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        for (long sellerId = 1_001L; sellerId < 1_001L + sellerCount; sellerId++) {
            long orderItemId = nextOrderItemId();
            insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemId, sellerId, 9_700L, createdAt);
            insertCandidate(PayoutEventType.SALE_FEE, orderItemId, sellerId, 300L, createdAt);
        }

        // when: 스케줄러/API와 같은 경로로 실행
        JobExecution execution = payoutJobRunner.run();

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<PayoutSnapshot> snapshots = snapshotRepository.findAll();
        assertThat(snapshots).hasSize(1);
        Long snapshotId = snapshots.get(0).getId();

        List<Payout> payouts = payoutRepository.findBySnapshotId(snapshotId);
        assertSoftly(softly -> {
            softly.assertThat(snapshots.get(0).getJobExecutionId()).isEqualTo(execution.getId());   // 실행 기록과 연결
            softly.assertThat(snapshotItemRepository.findBySnapshotId(snapshotId)).hasSize(sellerCount * 2);
            softly.assertThat(payouts).filteredOn(payout -> !payout.isSystem()).hasSize(sellerCount);   // 판매자 150명 전원
            softly.assertThat(payouts).filteredOn(Payout::isSystem).hasSize(1);                         // 2회차여도 시스템 1건
            // 발행 대상(판매자 몫만) 조회: 시스템 몫은 지갑으로 보내지 않음
            softly.assertThat(payoutRepository.findBySnapshotIdAndSystemFalse(snapshotId))
                    .hasSize(sellerCount)
                    .noneMatch(Payout::isSystem);
        });
    }

    @Test
    void 정산_대상이_없어도_Job은_COMPLETED로_끝나고_빈_스냅샷이_남는다() throws Exception {
        // when
        JobExecution execution = payoutJobRunner.run();

        // then: 대상이 없던 날도 "배치는 돌았다"는 기록이 남음
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(snapshotRepository.findAll()).hasSize(1);
        assertThat(payoutRepository.count()).isZero();
    }

    @Test
    void requestedAt_없이_실행하면_Job이_FAILED로_끝나고_스냅샷도_만들지_않는다() throws Exception {
        // given: 정산 대상이 있어도 (앱 시작 시 자동 실행 등 의도치 않은 실행 방지)
        insertSaleAmount(10L, 1_000L, LocalDateTime.now().minusDays(20));

        // when: 파라미터 없이 직접 실행
        JobExecution execution = jobOperator.start(payoutJob, new JobParameters());

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(snapshotRepository.findAll()).isEmpty();
        assertThat(payoutRepository.count()).isZero();
    }

    @Test
    void Job_실행_기록이_DB에_저장된다() throws Exception {
        // when: 정산 대상 없이 실행 (대상 유무와 상관없이 실행 기록은 남아야 함)
        JobExecution execution = payoutJobRunner.run();

        // then: 반환된 실행 ID로 메타 테이블을 직접 조회 → 메모리 저장이면 행이 없어 실패
        // (Spring Batch 메타 테이블은 엔티티가 없어 JDBC로 확인)
        String status = jdbcTemplate.queryForObject(
                "SELECT STATUS FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?",
                String.class, execution.getId());
        assertThat(status).isEqualTo(BatchStatus.COMPLETED.name());
    }

    @Test
    void 검증에_실패한_뒤_재실행하면_새_스냅샷으로_처음부터_정상_처리된다() throws Exception {
        // given: 이전 실행이 검증에서 실패한 상황 (draft는 남고 payout은 없음)
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        insertSaleAmount(10L, 1_000L, createdAt);
        Long failedSnapshotId = createSnapshot(null, LocalDateTime.now().minusDays(15));
        draftAll(failedSnapshotId, LIMIT);
        insertMissedSnapshotItem(failedSnapshotId, 20L, 2_000L);
        assertThatThrownBy(() -> payoutService.verify(failedSnapshotId)).isInstanceOf(RestApiException.class);

        // when: 재실행
        JobExecution execution = payoutJobRunner.run();

        // then: 새 스냅샷으로 판매자 10, 20 모두 정산되고, 실패한 스냅샷의 draft는 기록으로 남음
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<PayoutSnapshot> snapshots = snapshotRepository.findAll();
        assertThat(snapshots).hasSize(2);
        Long newSnapshotId = snapshots.stream()
                .map(PayoutSnapshot::getId)
                .filter(id -> !id.equals(failedSnapshotId))
                .findFirst()
                .orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(payoutRepository.findBySnapshotId(newSnapshotId))
                    .extracting(Payout::getPayeeId, Payout::getAmount)
                    .containsExactlyInAnyOrder(tuple(10L, 1_000L), tuple(20L, 2_000L));
            softly.assertThat(payoutRepository.findBySnapshotId(failedSnapshotId)).isEmpty();
            softly.assertThat(sellerDraftRepository.findBySnapshotId(failedSnapshotId)).isNotEmpty();
        });
    }

    // ---------- 도우미: 단계 실행 ----------

    // 서비스 직접 호출용 스냅샷 생성 (Job 실행 ID는 테스트마다 새로 발급)
    private Long createSnapshot(LocalDateTime from, LocalDateTime to) {
        return payoutService.createSnapshot(JOB_EXECUTION_ID_SEQ.incrementAndGet(), from, to);
    }

    // draftStep처럼 0이 나올 때까지 회차 반복
    private void draftAll(Long snapshotId, int limit) {
        while (payoutService.draft(snapshotId, limit) > 0) {
            // 회차 반복
        }
    }

    // 스냅샷 → draft → 검증 → 확정까지 실행하고 스냅샷 ID 반환 (발행은 확인 대상이 아니라 생략)
    private Long settleAll(LocalDateTime from, LocalDateTime to) {
        Long snapshotId = createSnapshot(from, to);
        draftAll(snapshotId, LIMIT);
        payoutService.verify(snapshotId);
        payoutService.commit(snapshotId);
        return snapshotId;
    }

    // ---------- 도우미: 데이터 준비 (JDBC) ----------

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

    // 검증 실패 상황 만들기: draft가 끝난 스냅샷 목록에, draft되지 않은 후보 1건을 끼워 넣음
    // (정상 흐름에서는 생기지 않는 상태라 서비스로 만들 수 없어 SQL로 직접 넣음)
    private void insertMissedSnapshotItem(Long snapshotId, Long sellerId, Long amount) {
        long orderItemId = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemId, sellerId, amount, LocalDateTime.now().minusDays(20));
        Long candidateId = candidateIdsOf(orderItemId).get(0);
        LocalDateTime now = toDbTime(LocalDateTime.now());
        jdbcTemplate.update(
                "INSERT INTO payout_snapshot_item (snapshot_id, candidate_id, amount, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                snapshotId, candidateId, amount, now, now);
    }

    // Hibernate는 LocalDateTime을 DB에 보낼 때 JVM 시간대 → hibernate.jdbc.time_zone으로 변환한다
    // jdbcTemplate은 이 변환을 거치지 않으므로, 직접 넣을 때도 같은 기준으로 바꿔야 QueryDSL 조회와 비교가 맞는다
    private LocalDateTime toDbTime(LocalDateTime jvmTime) {
        return jvmTime.atZone(ZoneId.systemDefault())        // 이 PC(JVM) 시간대 기준 시각으로 보고
                .withZoneSameInstant(ZoneId.of(dbTimeZone))  // 같은 순간을 DB 기준 시간대로 바꾼 뒤
                .toLocalDateTime();                          // 시간대 정보를 떼서 저장용 값으로
    }

    private long nextOrderItemId() {
        return ORDER_ITEM_ID_SEQ.incrementAndGet();
    }

    // 주문항목의 정산후보 id 목록 (JPA 조회)
    private List<Long> candidateIdsOf(long orderItemId) {
        List<Long> ids = new ArrayList<>();
        for (PayoutCandidateItem candidate : candidateRepository.findAllByOrderItemId(orderItemId)) {
            ids.add(candidate.getId());
        }
        return ids;
    }

    // 정산·draft·스냅샷은 전부, 정산 후보는 이 클래스 번호대만 지움 (FK 때문에 자식 테이블 먼저)
    // (리포지토리에는 삭제 기능이 없으므로 테스트 정리용으로만 SQL 직접 실행)
    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM payout_item");
        jdbcTemplate.update("DELETE FROM payout");
        jdbcTemplate.update("DELETE FROM payout_seller_draft_item");
        jdbcTemplate.update("DELETE FROM payout_seller_draft");
        jdbcTemplate.update("DELETE FROM payout_system_draft_item");
        jdbcTemplate.update("DELETE FROM payout_snapshot_item");
        jdbcTemplate.update("DELETE FROM payout_snapshot");
        jdbcTemplate.update("DELETE FROM payout_candidate_items WHERE order_item_id > ? AND order_item_id <= ?",
                ORDER_ITEM_ID_START, ORDER_ITEM_ID_END);
    }
}
