package com.ok.payout;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutItem;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.domain.PayoutSellerDraft;
import com.ok.payout.domain.PayoutSellerDraftItem;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.domain.PayoutSnapshotItem;
import com.ok.payout.domain.PayoutSystemDraftItem;
import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.payout.repository.PayoutCandidateItemRepository;
import com.ok.payout.repository.PayoutFailureRepository;
import com.ok.payout.repository.PayoutQueryRepository;
import com.ok.payout.repository.PayoutRepository;
import com.ok.payout.repository.PayoutSellerDraftRepository;
import com.ok.payout.repository.PayoutSnapshotItemRepository;
import com.ok.payout.repository.PayoutSnapshotRepository;
import com.ok.payout.repository.PayoutSystemDraftItemRepository;
import com.ok.payout.repository.PayoutWalletDeductionRepository;
import com.ok.payout.service.PayoutJobRunner;
import com.ok.payout.service.PayoutService;
import com.ok.payout.service.dto.PayoutVerifyResult;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 통합 테스트: 실제 Spring 컨텍스트 + MySQL 컨테이너에서 정산 배치(스냅샷 → draft → 검증 → 확정 → 발행) 확인
// - 정산후보는 created_at을 과거로 넣어야 하므로 리포지토리 대신 SQL로 직접 넣는다 (저장 시 Auditing이 현재 시각으로 덮어쓰기 때문)
// - SQL로 넣는 시각은 변환 없이 그대로 넣는다. 테스트 JVM(build.gradle.kts)과 Hibernate 시간대(application-test.yml)가
//   모두 Asia/Seoul로 고정되어(#93), JPA가 저장·조회하는 시각과 기준이 같기 때문이다
// - 결과 확인은 JPA 리포지토리로 한다 (팀 규칙). JDBC는 데이터 준비·정리에만 쓴다
// - 정산·스냅샷·draft는 이 클래스만 만들고 "마지막 정산일"이 범위 계산에 쓰이므로, 테스트 전후로 전부 비운다
class PayoutSettleIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 주문항목 ID 번호대 (PayoutCandidateIntegrationTest는 주문항목 9_000_000, 주문 9_500_000부터 사용)
    // 주문 ID에도 같은 번호를 쓰므로 정리는 order_id 이 범위로 함
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
    private PayoutWalletDeductionRepository payoutWalletDeductionRepository;

    @Autowired
    private PayoutFailureRepository failureRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 결과 검증 실패 기록이 바깥 트랜잭션 롤백과 상관없이 남는지 확인용 (verifyStep의 Step 트랜잭션 재현)
    @Autowired
    private PlatformTransactionManager transactionManager;

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
        assertThat(payoutService.draft(snapshotId, 2).sellerCount()).isEqualTo(2);
        assertThat(payoutService.draft(snapshotId, 2).sellerCount()).isEqualTo(1);
        assertThat(payoutService.draft(snapshotId, 2).sellerCount()).isZero();
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
        insertShippingFee(orderItemId, 10L, 3_000L, createdAt);   // 주문 ID = 주문항목 번호
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
    void draft가_놓친_후보의_주문만_결과_검증_실패로_빠지고_다른_주문은_정산된다() {
        // given: draft까지 끝난 뒤, draft가 놓친 후보(판매자 20) 1건이 스냅샷 목록에 있는 상황을 만듦
        insertSaleAmount(10L, 1_000L, TO.minusDays(1));
        Long snapshotId = createSnapshot(FROM, TO);
        draftAll(snapshotId, LIMIT);
        insertMissedSnapshotItem(snapshotId, 20L, 2_000L);

        // when
        PayoutVerifyResult result = payoutService.verify(snapshotId);
        payoutService.commit(snapshotId);

        // then: 놓친 후보만 원인으로 기록되고, 판매자 10은 정산됨 (전에는 그날 정산 전체가 멈춤)
        assertThat(result).isEqualTo(new PayoutVerifyResult(1, 1, 1));
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getSellerId, PayoutFailure::getReason, PayoutFailure::getDetail)
                .containsExactly(tuple(20L, PayoutFailureReason.VERIFY_MISMATCH, "[원인] draft 누락"));
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(10L, 1_000L));
    }

    @Test
    void 결과_검증에_걸린_주문은_모든_판매자_후보와_수수료가_빠지고_같은_판매자의_다른_주문은_정산된다() {
        // given: 주문 X = 판매자 10 대금·수수료 + 판매자 20 대금, 주문 Y = 판매자 10 대금·수수료
        LocalDateTime createdAt = TO.minusDays(1);
        long orderX = nextOrderItemId();
        givenDeduction(orderX);
        long orderItemX10 = nextOrderItemId();
        insertOrderCandidate(PayoutEventType.SALE_AMOUNT, orderX, orderItemX10, 10L, 9_700L, createdAt);
        insertOrderCandidate(PayoutEventType.SALE_FEE, orderX, orderItemX10, 10L, 300L, createdAt);
        insertOrderCandidate(PayoutEventType.SALE_AMOUNT, orderX, nextOrderItemId(), 20L, 19_400L, createdAt);
        long orderY = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderY, 10L, 970L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderY, 10L, 30L, createdAt);
        Long snapshotId = createSnapshot(FROM, TO);
        payoutService.validate(snapshotId);
        draftAll(snapshotId, LIMIT);
        // draft가 판매자 10의 주문 X 대금을 스냅샷과 다른 금액으로 옮긴 상황 (스냅샷 쪽 금액을 바꿔 재현)
        Long brokenCandidateId = candidateIdOf(orderX, 10L, PayoutEventType.SALE_AMOUNT);
        changeSnapshotAmount(snapshotId, brokenCandidateId, 9_000L);

        // when
        payoutService.verify(snapshotId);
        payoutService.commit(snapshotId);

        // then: 주문 X의 후보 3건(판매자 10·20)이 모두 결과 검증 실패, 원인 후보만 [원인]
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getOrderId, PayoutFailure::getSellerId, PayoutFailure::getReason)
                .containsExactlyInAnyOrder(
                        tuple(orderX, 10L, PayoutFailureReason.VERIFY_MISMATCH),
                        tuple(orderX, 10L, PayoutFailureReason.VERIFY_MISMATCH),
                        tuple(orderX, 20L, PayoutFailureReason.VERIFY_MISMATCH));
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .filteredOn(failure -> failure.getCandidateId().equals(brokenCandidateId))
                .extracting(PayoutFailure::getDetail)
                .containsExactly("[원인] 금액 다름(스냅샷 9000, draft 9700)");
        // then: 판매자 10은 주문 Y만, 시스템 몫은 주문 Y 수수료만. 항목이 모두 빠진 판매자 20은 정산을 만들지 않음
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(10L, 970L), tuple(null, 30L));
    }

    @Test
    void 결과_검증으로_잠긴_주문은_다음_스냅샷에서_빠진다() {
        // given: 1차 배치에서 주문 X가 결과 검증에 걸림 (주문 Y는 정상 정산)
        LocalDateTime createdAt = TO.minusDays(1);
        long orderX = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_700L, createdAt);
        long orderY = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderY, 20L, 19_400L, createdAt);
        Long firstSnapshotId = createSnapshot(FROM, TO);
        payoutService.validate(firstSnapshotId);
        draftAll(firstSnapshotId, LIMIT);
        changeSnapshotAmount(firstSnapshotId, candidateIdOf(orderX, 10L, PayoutEventType.SALE_AMOUNT), 1L);
        payoutService.verify(firstSnapshotId);
        payoutService.commit(firstSnapshotId);

        // when: 2차 배치
        Long secondSnapshotId = createSnapshot(FROM, TO);

        // then: 주문 X는 미정산인데도 잠겨 스냅샷에 들어가지 않음 (관리자 확인 전까지 자동 재시도하지 않음)
        assertThat(payoutRepository.findBySnapshotId(firstSnapshotId))
                .extracting(Payout::getPayeeId)
                .containsExactly(20L);
        assertThat(snapshotItemRepository.findBySnapshotId(secondSnapshotId)).isEmpty();
    }

    @Test
    void 같은_스냅샷을_다시_검증해도_결과_검증_실패_기록은_중복되지_않는다() {
        // given: 1차 검증에서 놓친 후보의 주문이 빠짐
        insertSaleAmount(10L, 1_000L, TO.minusDays(1));
        Long snapshotId = createSnapshot(FROM, TO);
        draftAll(snapshotId, LIMIT);
        insertMissedSnapshotItem(snapshotId, 20L, 2_000L);
        payoutService.verify(snapshotId);

        // when: 같은 스냅샷을 다시 검증
        PayoutVerifyResult result = payoutService.verify(snapshotId);

        // then: 이미 빠진 주문은 다시 보지 않아 새 기록이 없고, 빠진 주문 수·정산할 항목 수는 같음
        assertThat(result).isEqualTo(new PayoutVerifyResult(1, 1, 0));
        assertThat(failureRepository.findBySnapshotId(snapshotId)).hasSize(1);
    }

    @Test
    void 스냅샷에_없는_후보가_draft에_들어가면_그_주문이_빠지고_수수료도_시스템_몫에_들어가지_않는다() {
        // given: 정상 주문 Y(판매자 10 대금·수수료)를 draft한 뒤, 스냅샷에 없는 주문 Z의 수수료 후보가 시스템 몫에 들어간 상황
        LocalDateTime createdAt = TO.minusDays(1);
        long orderY = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderY, 10L, 9_700L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderY, 10L, 300L, createdAt);
        Long snapshotId = createSnapshot(FROM, TO);
        draftAll(snapshotId, LIMIT);
        long orderZ = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_FEE, orderZ, 20L, 600L, createdAt);
        insertSystemDraftItem(snapshotId, candidateIdOf(orderZ, 20L, PayoutEventType.SALE_FEE));

        // when
        payoutService.verify(snapshotId);
        payoutService.commit(snapshotId);

        // then: 주문 Z의 후보가 원인으로 기록되고, 시스템 몫에는 주문 Y 수수료만
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getOrderId, PayoutFailure::getDetail)
                .containsExactly(tuple(orderZ, "[원인] 스냅샷에 없는 항목"));
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(10L, 9_700L), tuple(null, 300L));
    }

    @Test
    void 다른_판매자_정산에_묶인_항목은_금액이_같아도_그_주문이_빠진다() {
        // given: 판매자 10(주문 X), 20(주문 Y)을 draft한 뒤, 판매자 10의 항목이 판매자 20의 헤더 아래로 들어간 상황
        // 판매자 합계 검사에는 걸리지 않게 두 헤더 금액도 맞춰 둠 (항목 단위 검사만으로 잡는지 확인)
        LocalDateTime createdAt = TO.minusDays(1);
        long orderX = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_700L, createdAt);
        long orderY = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderY, 20L, 19_400L, createdAt);
        Long snapshotId = createSnapshot(FROM, TO);
        draftAll(snapshotId, LIMIT);
        moveSellerDraftItem(snapshotId, candidateIdOf(orderX, 10L, PayoutEventType.SALE_AMOUNT), 10L, 20L);

        // when
        payoutService.verify(snapshotId);
        payoutService.commit(snapshotId);

        // then: 주문 X는 빠지고, 판매자 20은 자기 주문 Y만 받음 (판매자 10의 돈이 20에게 가지 않음)
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getOrderId, PayoutFailure::getDetail)
                .containsExactly(tuple(orderX, "[원인] 다른 판매자 정산에 묶임(후보 10, 헤더 20)"));
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(20L, 19_400L));
    }

    @Test
    void 주문이_다르게_들어간_draft_항목은_그_주문에_실패_기록이_없어도_정산에서_빠진다() {
        // given: 판매자 10(주문 X), 20(주문 Y)을 draft한 뒤, 주문 X 대금 항목의 주문 ID가 스냅샷에 없는 주문으로 들어간 상황
        // 실패 기록의 주문은 후보에서 복사(X)하므로 draft 항목의 주문으로는 거를 수 없음 → 후보 기준 제외가 막아야 함
        LocalDateTime createdAt = TO.minusDays(1);
        long orderX = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_700L, createdAt);
        long orderY = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderY, 20L, 19_400L, createdAt);
        Long snapshotId = createSnapshot(FROM, TO);
        draftAll(snapshotId, LIMIT);
        long wrongOrderId = nextOrderItemId();
        Long candidateX = candidateIdOf(orderX, 10L, PayoutEventType.SALE_AMOUNT);
        jdbcTemplate.update("UPDATE payout_seller_draft_item SET order_id = ? WHERE snapshot_id = ? AND candidate_id = ?",
                wrongOrderId, snapshotId, candidateX);

        // when
        payoutService.verify(snapshotId);
        payoutService.commit(snapshotId);

        // then: 판매자 10의 돈은 나가지 않고, 판매자 20만 정산
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getOrderId, PayoutFailure::getDetail)
                .containsExactly(tuple(orderX, "[원인] 주문 다름(스냅샷 " + orderX + ", draft " + wrongOrderId + ")"));
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(20L, 19_400L));
    }

    @Test
    void 판매자_합계가_항목_합계와_다르면_전용_에러로_멈추고_실패를_기록하지_않는다() {
        // given: draft 뒤 판매자 10의 헤더 금액만 바뀜 (draft를 만드는 코드가 망가진 상황), 놓친 후보도 함께 있음
        insertSaleAmount(10L, 1_000L, TO.minusDays(1));
        Long snapshotId = createSnapshot(FROM, TO);
        draftAll(snapshotId, LIMIT);
        insertMissedSnapshotItem(snapshotId, 20L, 2_000L);
        changeSellerDraftAmount(snapshotId, 10L, 999L);

        // when & then: 망가진 코드로 판정한 결과라 주문을 잠그지 않고 멈춤
        assertThatThrownBy(() -> payoutService.verify(snapshotId))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.DRAFT_SELLER_TOTAL_MISMATCH));
        assertThat(failureRepository.findBySnapshotId(snapshotId)).isEmpty();
    }

    @Test
    void 실패_기록이_있는_후보가_draft에_들어가면_전용_에러로_멈춘다() {
        // given: draft 뒤 이미 draft된 후보에 실패 기록이 생긴 상황 (draft 대상 조회 조건이 실패 후보를 거르지 못한 것과 같은 상태)
        long orderX = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 1_000L, TO.minusDays(1));
        Long snapshotId = createSnapshot(FROM, TO);
        draftAll(snapshotId, LIMIT);
        insertFailure(snapshotId, candidateIdOf(orderX, 10L, PayoutEventType.SALE_AMOUNT), orderX, 10L);

        // when & then
        assertThatThrownBy(() -> payoutService.verify(snapshotId))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.DRAFT_INCLUDES_FAILED_CANDIDATE));
    }

    @Test
    void 결과_검증_실패_기록은_검증한_트랜잭션이_롤백돼도_남는다() {
        // given: 정산할 것이 0건이 되어 verifyStep이 실패하는 상황 (Step 트랜잭션 안에서 검증 후 예외로 롤백)
        long orderX = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 1_000L, TO.minusDays(1));
        Long snapshotId = createSnapshot(FROM, TO);
        draftAll(snapshotId, LIMIT);
        changeSnapshotAmount(snapshotId, candidateIdOf(orderX, 10L, PayoutEventType.SALE_AMOUNT), 1L);
        TransactionTemplate stepTransaction = new TransactionTemplate(transactionManager);

        // when
        assertThatThrownBy(() -> stepTransaction.executeWithoutResult(status -> {
            PayoutVerifyResult result = payoutService.verify(snapshotId);
            assertThat(result.everyOrderExcluded()).isTrue();
            throw new IllegalStateException("verifyStep 실패 재현");
        })).hasMessage("verifyStep 실패 재현");

        // then: 실패 기록(= 주문 잠금)은 별도 트랜잭션으로 커밋돼 남고, 다음 스냅샷에서 그 주문이 빠짐
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getOrderId, PayoutFailure::getReason)
                .containsExactly(tuple(orderX, PayoutFailureReason.VERIFY_MISMATCH));
        assertThat(snapshotItemRepository.findBySnapshotId(createSnapshot(FROM, TO))).isEmpty();
    }

    @Test
    void 헤더와_스냅샷이_다른_판매자_draft_항목은_결과_검증이_못_보므로_정산하지_않는다() {
        // given: 판매자 10의 draft 뒤, 스냅샷에 없는 후보 Z의 항목이 다른 스냅샷 번호로 판매자 10의 헤더 아래에 들어간 상황
        // verify는 헤더·항목 스냅샷이 모두 이번 스냅샷인 항목만 보므로 이 항목을 보지 못함 → commit이 직접 걸러야 함
        // 판매자 합계 검사에 걸리지 않게 헤더 금액은 항목 합계로 맞춰 둠
        LocalDateTime createdAt = TO.minusDays(1);
        long orderX = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_700L, createdAt);
        Long snapshotId = createSnapshot(FROM, TO);
        draftAll(snapshotId, LIMIT);
        long orderZ = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderZ, 10L, 5_000L, createdAt);
        Long otherSnapshotId = createSnapshot(FROM, TO);
        insertSellerDraftItemUnderHeader(snapshotId, 10L, otherSnapshotId, candidateIdOf(orderZ, 10L, PayoutEventType.SALE_AMOUNT));

        // when
        payoutService.verify(snapshotId);
        payoutService.commit(snapshotId);

        // then: 판매자 10은 주문 X만 받음 (후보 Z의 5,000원은 나가지 않음)
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(10L, 9_700L));
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
    void 배송비_후보는_정산_항목까지_주문_ID와_함께_저장되고_주문상품_ID는_비어_있다() {
        // given: 판매자 10의 주문 1건 (대금 + 묶음 배송비)
        long orderItemId = nextOrderItemId();
        long orderId = orderItemId;   // 상품 하나 = 주문 하나로 단순화 (insertCandidate와 같은 규칙)
        LocalDateTime createdAt = TO.minusDays(1);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemId, 10L, 9_700L, createdAt);
        insertShippingFee(orderId, 10L, 3_000L, createdAt);

        // when
        Long snapshotId = settleAll(FROM, TO);

        // then: 판매자 정산 1건에 대금·배송비 항목이 모두 주문 ID와 함께 들어가고, 배송비만 주문상품 ID가 없음
        Payout sellerPayout = null;
        for (Payout payout : payoutRepository.findBySnapshotId(snapshotId)) {
            if (!payout.isSystem()) {
                sellerPayout = payout;
            }
        }
        assertThat(sellerPayout).isNotNull();
        assertThat(sellerPayout.getAmount()).isEqualTo(12_700L);
        assertThat(sellerPayout.getItems())
                .extracting(PayoutItem::getEventType, PayoutItem::getOrderId, PayoutItem::getOrderItemId)
                .containsExactlyInAnyOrder(
                        tuple(PayoutEventType.SALE_AMOUNT, orderId, orderItemId),
                        tuple(PayoutEventType.SALE_SHIPPING_FEE, orderId, null)
                );
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
    void 지갑_차감_기록이_없는_주문의_후보만_실패로_기록되어_빠지고_나머지는_정산된다() throws Exception {
        // given: 주문 A(판매자 10)는 차감 기록 있음, 주문 B(판매자 20)는 차감 기록 없음. 둘 다 생성 20일 전
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        long orderA = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderA, 10L, 9_700L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderA, 10L, 300L, createdAt);
        long orderB = nextOrderItemId();
        insertCandidateWithoutDeduction(PayoutEventType.SALE_AMOUNT, orderB, 20L, 19_400L, createdAt);
        insertCandidateWithoutDeduction(PayoutEventType.SALE_FEE, orderB, 20L, 600L, createdAt);

        // when
        JobExecution execution = payoutJobRunner.run();

        // then: Job은 끝까지 성공하고(실패가 있어도 verify 통과), 주문 B의 후보 2건만 실패로 남음
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long snapshotId = snapshotRepository.findAll().get(0).getId();
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getCandidateId, PayoutFailure::getOrderId, PayoutFailure::getSellerId,
                        PayoutFailure::getReason, PayoutFailure::getDetail)
                .containsExactlyInAnyOrder(
                        tuple(candidateIdsOf(orderB).get(0), orderB, 20L, PayoutFailureReason.DEDUCTION_MISSING, "결제 기록 없음"),
                        tuple(candidateIdsOf(orderB).get(1), orderB, 20L, PayoutFailureReason.DEDUCTION_MISSING, "결제 기록 없음"));

        // then: 정산은 주문 A만 (판매자 10 몫 9,700원 + 시스템 몫 300원). 판매자 20 정산은 없음
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::isSystem, Payout::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(10L, false, 9_700L),
                        tuple(null, true, 300L));
    }

    @Test
    void 지갑_차감_기록이_없는_주문의_배송비_후보도_주문상품_ID_없이_실패로_기록되어_빠진다() throws Exception {
        // given: 차감 기록이 없는 주문 B에 판매 대금과 배송비(order_item_id NULL)가 함께 있음
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        long orderB = nextOrderItemId();
        insertCandidateWithoutDeduction(PayoutEventType.SALE_AMOUNT, orderB, 20L, 19_400L, createdAt);
        insertShippingFeeWithoutDeduction(orderB, 20L, 3_000L, createdAt);

        // when
        JobExecution execution = payoutJobRunner.run();

        // then: 주문 단위 검사라 배송비도 같은 주문으로 걸리고, 실패 기록의 주문상품 ID는 비어 있음
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long snapshotId = snapshotRepository.findAll().get(0).getId();
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getOrderId, PayoutFailure::getOrderItemId, PayoutFailure::getReason)
                .containsExactlyInAnyOrder(
                        tuple(orderB, orderB, PayoutFailureReason.DEDUCTION_MISSING),   // 판매 대금
                        tuple(orderB, null, PayoutFailureReason.DEDUCTION_MISSING));    // 배송비
        assertThat(payoutRepository.findBySnapshotId(snapshotId)).isEmpty();
    }

    @Test
    void 같은_스냅샷에_입력_검증을_두_번_실행해도_실패_기록은_중복되지_않는다() {
        // given: 차감 기록이 없는 후보 1건이 든 스냅샷
        long orderItemId = nextOrderItemId();
        insertCandidateWithoutDeduction(PayoutEventType.SALE_AMOUNT, orderItemId, 20L, 19_400L, FROM.plusDays(1));
        Long snapshotId = createSnapshot(FROM, TO);

        // when: 같은 스냅샷을 두 번 검증
        int first = payoutService.validate(snapshotId);
        int second = payoutService.validate(snapshotId);

        // then: 두 번째는 이미 기록된 후보를 다시 조회하지 않아 새로 저장할 게 없음
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(failureRepository.findBySnapshotId(snapshotId)).hasSize(1);
    }

    @Test
    void 차감_기록이_없어_빠진_후보는_다음_배치_전에_차감_기록이_들어오면_그때_정산된다() throws Exception {
        // given: 차감 기록이 없는 주문 B (판매자 20)
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        long orderB = nextOrderItemId();
        insertCandidateWithoutDeduction(PayoutEventType.SALE_AMOUNT, orderB, 20L, 19_400L, createdAt);

        // when: 1차 배치 → 빠짐, 그 뒤 지갑 차감 기록이 늦게 들어옴 → 2차 배치
        JobExecution first = payoutJobRunner.run();
        givenDeduction(orderB);
        JobExecution second = payoutJobRunner.run();

        // then: 1차는 실패 기록만 있고 정산 없음 (잠그지 않아 미정산 후보로 남음)
        Long firstSnapshotId = snapshotIdOf(first);
        assertThat(failureRepository.findBySnapshotId(firstSnapshotId)).hasSize(1);
        assertThat(payoutRepository.findBySnapshotId(firstSnapshotId)).isEmpty();

        // then: 2차에서 다시 검사해 통과하고 정산됨
        Long secondSnapshotId = snapshotIdOf(second);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(failureRepository.findBySnapshotId(secondSnapshotId)).isEmpty();
        assertThat(payoutRepository.findBySnapshotId(secondSnapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(20L, 19_400L));
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
        // given: 이전 실행이 결과 검증의 코드 고장 검사(판매자 합계 불일치)로 멈춘 상황 (draft는 남고 payout·실패 기록은 없음)
        // 코드 고장으로 멈출 때는 주문을 잠그지 않으므로, 코드를 고친 뒤 재실행하면 모두 다시 처리됨
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        insertSaleAmount(10L, 1_000L, createdAt);
        insertSaleAmount(20L, 2_000L, createdAt);
        Long failedSnapshotId = createSnapshot(null, LocalDateTime.now().minusDays(15));
        draftAll(failedSnapshotId, LIMIT);
        changeSellerDraftAmount(failedSnapshotId, 10L, 999L);
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

    // ---------- validateStep 금액 검사 (#195) ----------

    @Test
    void 주문_후보_합계가_차감액을_넘으면_금액_불일치로_빠지고_다른_주문은_정산된다() {
        // given: 주문 X(판매자 10)는 후보 합계 10,000 > 차감액 9,999, 주문 Y(판매자 20)는 정상
        LocalDateTime createdAt = TO.minusDays(1);
        long orderX = nextOrderItemId();
        givenDeduction(orderX, 9_999L);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_700L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderX, 10L, 300L, createdAt);
        long orderY = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderY, 20L, 19_400L, createdAt);

        // when
        Long snapshotId = settleAll(FROM, TO);

        // then: 주문 X 후보 2건이 초과 청구로 빠지고, 정산은 주문 Y만
        String detail = "주문 합계 초과: 차감액 9999, 후보 합계 10000 (어느 주문상품이 틀렸는지 판별 불가)";
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getOrderId, PayoutFailure::getReason, PayoutFailure::getDetail)
                .containsExactly(
                        tuple(orderX, PayoutFailureReason.AMOUNT_MISMATCH, detail),
                        tuple(orderX, PayoutFailureReason.AMOUNT_MISMATCH, detail));
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(20L, 19_400L));
    }

    @Test
    void 금액_불일치로_잠긴_주문은_다음_스냅샷에서_빠진다() {
        // given: 1차 배치에서 주문 X가 초과 청구로 잠김
        long orderX = nextOrderItemId();
        givenDeduction(orderX, 9_000L);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_700L, TO.minusDays(1));
        Long firstSnapshotId = settleAll(FROM, TO);

        // when: 2차 배치
        Long secondSnapshotId = createSnapshot(FROM, TO);

        // then: 1차에서 금액 불일치로 빠져 정산되지 않음 (미정산 후보로 남음)
        // 이걸 먼저 확인하는 이유: 1차에서 그냥 정산돼 버려도 2차 스냅샷은 비므로, 아래 확인만으로는 잠금 때문인지 알 수 없음
        assertThat(failureRepository.findBySnapshotId(firstSnapshotId))
                .extracting(PayoutFailure::getReason)
                .containsExactly(PayoutFailureReason.AMOUNT_MISMATCH);
        assertThat(payoutRepository.findBySnapshotId(firstSnapshotId)).isEmpty();
        // then: 미정산인데도 잠긴 주문이라 스냅샷에 들어가지 않음 (차감 기록 없음과 달리 다시 검사하지 않음)
        assertThat(snapshotItemRepository.findBySnapshotId(secondSnapshotId)).isEmpty();
    }

    @Test
    void 잠긴_주문에_나중에_생긴_후보도_다음_스냅샷에서_빠진다() {
        // given: 주문 X가 잠긴 뒤, 같은 주문의 다른 주문상품과 조정 후보가 새로 들어옴
        LocalDateTime createdAt = TO.minusDays(1);
        long orderX = nextOrderItemId();
        givenDeduction(orderX, 9_000L);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_700L, createdAt);
        settleAll(FROM, TO);
        insertOrderCandidate(PayoutEventType.SALE_AMOUNT, orderX, nextOrderItemId(), 20L, 1_000L, createdAt);
        insertAdjustment(PayoutEventType.SALE_AMOUNT, orderX, 10L, -700L, 1L, createdAt);

        // when
        Long secondSnapshotId = createSnapshot(FROM, TO);

        // then: 스냅샷 번호와 상관없이 주문 단위로 잠겨, 새 후보도 들어가지 않음
        assertThat(snapshotItemRepository.findBySnapshotId(secondSnapshotId)).isEmpty();
    }

    @Test
    void 결과_검증에_실패한_Job이_남긴_잠금도_다음_스냅샷에서_작동한다() {
        // given: 입력 검증에서 주문 X를 잠근 뒤 결과 검증(verify)이 코드 고장 검사(판매자 합계 불일치)로 멈춰 정산 없이 끝남
        // 판매자 20의 후보는 다음 스냅샷이 비어 있지 않게 하려고 둠
        long orderX = nextOrderItemId();
        givenDeduction(orderX, 9_000L);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_700L, TO.minusDays(1));
        insertSaleAmount(20L, 2_000L, TO.minusDays(1));
        Long failedSnapshotId = createSnapshot(FROM, TO);
        payoutService.validate(failedSnapshotId);
        draftAll(failedSnapshotId, LIMIT);
        changeSellerDraftAmount(failedSnapshotId, 20L, 1L);
        assertThatThrownBy(() -> payoutService.verify(failedSnapshotId)).isInstanceOf(RestApiException.class);

        // when: 다음 배치
        Long nextSnapshotId = createSnapshot(FROM, TO);

        // then: 둘 다 미정산 후보지만, 판매자 20의 후보만 들어가고 주문 X는 실패한 Job이 남긴 잠금 때문에 빠짐
        assertThat(snapshotItemRepository.findBySnapshotId(nextSnapshotId))
                .extracting(PayoutSnapshotItem::getAmount)
                .containsExactly(2_000L);
    }

    @Test
    void 초과_청구_합계에는_이미_정산된_같은_주문의_후보도_들어간다() {
        // given: 주문 X(차감액 15,000)의 주문상품 1(10,000)은 지난 배치에 정산됨
        LocalDateTime createdAt = TO.minusDays(1);
        long orderX = nextOrderItemId();
        givenDeduction(orderX, 15_000L);
        long firstItem = nextOrderItemId();
        insertOrderCandidate(PayoutEventType.SALE_AMOUNT, orderX, firstItem, 10L, 9_700L, createdAt);
        insertOrderCandidate(PayoutEventType.SALE_FEE, orderX, firstItem, 10L, 300L, createdAt);
        settleAll(FROM, TO);
        // 그 뒤 주문상품 2(6,000)가 구매확정됨 → 주문 합계 16,000 > 15,000
        long secondItem = nextOrderItemId();
        insertOrderCandidate(PayoutEventType.SALE_AMOUNT, orderX, secondItem, 10L, 5_820L, createdAt);
        insertOrderCandidate(PayoutEventType.SALE_FEE, orderX, secondItem, 10L, 180L, createdAt);

        // when
        Long snapshotId = settleAll(FROM, TO);

        // then: 이번 스냅샷 후보(주문상품 2)만으로는 6,000이라 통과하지만, 정산된 후보까지 더해 초과로 잡힘
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getOrderItemId, PayoutFailure::getReason)
                .containsExactly(
                        tuple(secondItem, PayoutFailureReason.AMOUNT_MISMATCH),
                        tuple(secondItem, PayoutFailureReason.AMOUNT_MISMATCH));
        assertThat(payoutRepository.findBySnapshotId(snapshotId)).isEmpty();
    }

    @Test
    void 주문_후보_합계가_차감액보다_작으면_정상_정산된다() {
        // given: 후보 합계 13,000 < 차감액 13,500 (차감액에는 있지만 정산후보로 오지 않는 금액이 있는 경우)
        LocalDateTime createdAt = TO.minusDays(1);
        long orderX = nextOrderItemId();
        givenDeduction(orderX, 13_500L);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_700L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderX, 10L, 300L, createdAt);
        insertShippingFee(orderX, 10L, 3_000L, createdAt);

        // when
        Long snapshotId = settleAll(FROM, TO);

        // then
        assertThat(failureRepository.findBySnapshotId(snapshotId)).isEmpty();
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(10L, 12_700L), tuple(null, 300L));
    }

    @Test
    void 수수료가_틀리면_원인은_금액_불일치_같은_주문의_다른_판매자_후보는_보류되고_Job은_완료된다() throws Exception {
        // given: 주문 X에 판매자 10(수수료 500, 계산은 309)과 판매자 20(정상 + 배송비), 주문 Y(판매자 30)는 정상
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        long orderX = nextOrderItemId();
        givenDeduction(orderX, 40_000L);
        long wrongItem = nextOrderItemId();
        insertOrderCandidate(PayoutEventType.SALE_AMOUNT, orderX, wrongItem, 10L, 9_800L, createdAt);
        insertOrderCandidate(PayoutEventType.SALE_FEE, orderX, wrongItem, 10L, 500L, createdAt);
        long rightItem = nextOrderItemId();
        insertOrderCandidate(PayoutEventType.SALE_AMOUNT, orderX, rightItem, 20L, 19_400L, createdAt);
        insertOrderCandidate(PayoutEventType.SALE_FEE, orderX, rightItem, 20L, 600L, createdAt);
        insertShippingFee(orderX, 20L, 3_000L, createdAt);
        long orderY = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderY, 30L, 9_700L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderY, 30L, 300L, createdAt);

        // when
        JobExecution execution = payoutJobRunner.run();

        // then: Job은 끝까지 성공, 주문 X의 후보 5건은 1건씩 실패로 기록
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long snapshotId = snapshotIdOf(execution);
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getOrderItemId, PayoutFailure::getSellerId, PayoutFailure::getReason)
                .containsExactlyInAnyOrder(
                        tuple(wrongItem, 10L, PayoutFailureReason.AMOUNT_MISMATCH),
                        tuple(wrongItem, 10L, PayoutFailureReason.AMOUNT_MISMATCH),
                        tuple(rightItem, 20L, PayoutFailureReason.ORDER_LOCKED),
                        tuple(rightItem, 20L, PayoutFailureReason.ORDER_LOCKED),
                        tuple(null, 20L, PayoutFailureReason.ORDER_LOCKED));   // 배송비

        // then: 정산은 주문 Y만
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(30L, 9_700L), tuple(null, 300L));
    }

    @Test
    void 한_주문에_수수료가_틀린_주문상품이_2개여도_후보마다_실패_기록이_1건이고_Job은_완료된다() throws Exception {
        // given: 주문 X에 판매자 10, 20의 주문상품 둘 다 수수료가 틀림
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        long orderX = nextOrderItemId();
        givenDeduction(orderX, 40_000L);
        long firstItem = nextOrderItemId();
        insertOrderCandidate(PayoutEventType.SALE_AMOUNT, orderX, firstItem, 10L, 9_800L, createdAt);
        insertOrderCandidate(PayoutEventType.SALE_FEE, orderX, firstItem, 10L, 500L, createdAt);
        long secondItem = nextOrderItemId();
        insertOrderCandidate(PayoutEventType.SALE_AMOUNT, orderX, secondItem, 20L, 19_000L, createdAt);
        insertOrderCandidate(PayoutEventType.SALE_FEE, orderX, secondItem, 20L, 1_000L, createdAt);

        // when
        JobExecution execution = payoutJobRunner.run();

        // then: 실패 기록 UK에 걸려 롤백되지 않고 4건이 1건씩 남음
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(failureRepository.findBySnapshotId(snapshotIdOf(execution)))
                .extracting(PayoutFailure::getCandidateId)
                .containsExactlyInAnyOrderElementsOf(candidateIdsOf(orderX));
    }

    @Test
    void 차감_기록이_없는_주문은_금액_검사를_하지_않고_차감_기록_없음만_기록된다() {
        // given: 차감 기록이 없고 수수료도 틀린 주문
        long orderX = nextOrderItemId();
        insertCandidateWithoutDeduction(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_800L, TO.minusDays(1));
        insertCandidateWithoutDeduction(PayoutEventType.SALE_FEE, orderX, 10L, 500L, TO.minusDays(1));
        Long snapshotId = createSnapshot(FROM, TO);

        // when
        payoutService.validate(snapshotId);

        // then: 잠그지 않는 사유만 남아, 차감 기록이 들어오면 다음 배치에서 다시 검사됨
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getReason)
                .containsExactly(PayoutFailureReason.DEDUCTION_MISSING, PayoutFailureReason.DEDUCTION_MISSING);
    }

    @Test
    void 같은_스냅샷에_입력_검증을_두_번_실행해도_금액_불일치_기록은_중복되지_않는다() {
        // given: 초과 청구 주문 1건이 든 스냅샷
        long orderX = nextOrderItemId();
        givenDeduction(orderX, 9_000L);
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_700L, TO.minusDays(1));
        Long snapshotId = createSnapshot(FROM, TO);

        // when
        int first = payoutService.validate(snapshotId);
        int second = payoutService.validate(snapshotId);

        // then
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(failureRepository.findBySnapshotId(snapshotId)).hasSize(1);
    }

    @Test
    void 조정_후보가_있어도_수수료_검사에서_잘못_잡지_않는다() {
        // given: 원래 대금 9,700·수수료 300(정상)에 수수료 조정 -100
        LocalDateTime createdAt = TO.minusDays(1);
        long orderX = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_700L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderX, 10L, 300L, createdAt);
        insertAdjustment(PayoutEventType.SALE_FEE, orderX, 10L, -100L, 1L, createdAt);

        // when
        Long snapshotId = settleAll(FROM, TO);

        // then: 조정까지 포함해 정산됨 (시스템 몫 300 - 100)
        assertThat(failureRepository.findBySnapshotId(snapshotId)).isEmpty();
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(10L, 9_700L), tuple(null, 200L));
    }

    @Test
    void 대금_수수료_짝이_없는_주문상품은_수수료_검사로_잠기지_않는다() {
        // given: 수수료 후보만 있음 (계산식으로 맞출 판매 대금이 없음)
        long orderX = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_FEE, orderX, 10L, 999L, TO.minusDays(1));

        // when
        Long snapshotId = settleAll(FROM, TO);

        // then
        assertThat(failureRepository.findBySnapshotId(snapshotId)).isEmpty();
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(null, 999L));
    }

    @Test
    void 같은_주문상품의_대금이_이미_정산됐어도_나중에_온_수수료를_잘못_잠그지_않는다() {
        // 이 테스트는 "짝의 한쪽이 없다고 0으로 보고 틀렸다고 판정하지 않는다"를 확인함
        // 정산된 대금을 실제로 가져와 비교하는지는 바로 아래 반대 경우 테스트(틀린 수수료 → 잠김)가 확인함
        // given: 판매 대금만 먼저 정산됨
        LocalDateTime createdAt = TO.minusDays(1);
        long orderX = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_700L, createdAt);
        settleAll(FROM, TO);
        // 그 뒤 같은 주문상품의 수수료 후보가 들어옴 (정산된 대금과 짝이 맞음)
        insertCandidate(PayoutEventType.SALE_FEE, orderX, 10L, 300L, createdAt);

        // when
        Long snapshotId = settleAll(FROM, TO);

        // then
        assertThat(failureRepository.findBySnapshotId(snapshotId)).isEmpty();
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(null, 300L));
    }

    @Test
    void 이미_정산된_대금과_짝을_맞춰_나중에_온_수수료가_틀리면_잠기고_다음_스냅샷에서_빠진다() {
        // given: 판매 대금 9,700이 먼저 정산된 뒤 같은 주문상품의 수수료 500이 들어옴 (판매 금액 10,200의 수수료는 306)
        // 위 테스트의 반대 경우: 스냅샷 밖(정산된) 대금을 가져오지 못하면 짝이 없어 검사를 건너뛰므로 이 테스트가 실패함
        LocalDateTime createdAt = TO.minusDays(1);
        long orderX = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderX, 10L, 9_700L, createdAt);
        settleAll(FROM, TO);
        insertCandidate(PayoutEventType.SALE_FEE, orderX, 10L, 500L, createdAt);

        // when
        Long snapshotId = settleAll(FROM, TO);
        Long nextSnapshotId = createSnapshot(FROM, TO);

        // then: 수수료 후보가 금액 불일치로 빠지고, 수수료 검사로 잠긴 주문도 다음 스냅샷에 들어가지 않음
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getReason, PayoutFailure::getDetail)
                .containsExactly(tuple(PayoutFailureReason.AMOUNT_MISMATCH,
                        "수수료 불일치: 기대값 306, 실제값 500 (판매 금액 10200 기준)"));
        assertThat(payoutRepository.findBySnapshotId(snapshotId)).isEmpty();
        assertThat(snapshotItemRepository.findBySnapshotId(nextSnapshotId)).isEmpty();
    }

    @Test
    void 같은_주문의_상품이_다른_날_구매확정돼도_먼저_들어온_상품은_기다리지_않고_정산된다() {
        // given: 주문 X(차감액 30,000)의 주문상품 1은 기간 안, 주문상품 2는 기간 뒤(아직 대기 중)에 구매확정
        long orderX = nextOrderItemId();
        givenDeduction(orderX, 30_000L);
        long firstItem = nextOrderItemId();
        insertOrderCandidate(PayoutEventType.SALE_AMOUNT, orderX, firstItem, 10L, 9_700L, TO.minusDays(1));
        insertOrderCandidate(PayoutEventType.SALE_FEE, orderX, firstItem, 10L, 300L, TO.minusDays(1));
        long secondItem = nextOrderItemId();
        insertOrderCandidate(PayoutEventType.SALE_AMOUNT, orderX, secondItem, 10L, 19_400L, TO.plusDays(1));
        insertOrderCandidate(PayoutEventType.SALE_FEE, orderX, secondItem, 10L, 600L, TO.plusDays(1));

        // when
        Long snapshotId = settleAll(FROM, TO);

        // then: 주문상품 1만 정산됨 (주문상품 2는 대기 기간이 지나면 다음 배치에서)
        assertThat(failureRepository.findBySnapshotId(snapshotId)).isEmpty();
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(10L, 9_700L), tuple(null, 300L));
    }

    // ---------- draftStep 판매자 단위 격리 (#90) ----------

    @Test
    void 한_판매자_draft_저장이_실패해도_같은_회차의_다른_판매자와_이후_회차는_정산된다() {
        // given: 판매자 20, 10, 30 각각 대금·수수료. 생성 순서로 1회차 [20, 10], 2회차 [30]
        // 실패할 판매자 20을 회차 맨 앞에 둠: 실패 직후 같은 회차의 다음 판매자(10)가 이어서 저장되는지까지 확인하려고
        long orderItem20 = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItem20, 20L, 19_400L, TO.minusDays(3));
        insertCandidate(PayoutEventType.SALE_FEE, orderItem20, 20L, 600L, TO.minusDays(3));
        long orderItem10 = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItem10, 10L, 9_700L, TO.minusDays(2));
        insertCandidate(PayoutEventType.SALE_FEE, orderItem10, 10L, 300L, TO.minusDays(2));
        long orderItem30 = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItem30, 30L, 970L, TO.minusDays(1));
        insertCandidate(PayoutEventType.SALE_FEE, orderItem30, 30L, 30L, TO.minusDays(1));
        Long snapshotId = createSnapshot(FROM, TO);
        payoutService.validate(snapshotId);
        // 판매자 20 저장에서 DB 오류가 나게 막아 둠
        blockSellerDraft(snapshotId, 20L);

        // when: 한 회차 2명씩 draft (1회차에서 판매자 20이 실패)
        draftAll(snapshotId, 2);
        // 오류가 사라진 뒤 검증·확정
        unblockSellerDraft(snapshotId, 20L);
        payoutService.verify(snapshotId);
        payoutService.commit(snapshotId);

        // then: 판매자 20의 후보 2건만 UNEXPECTED_ERROR (예외 종류가 상세에 남음)
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getSellerId, PayoutFailure::getReason)
                .containsExactly(
                        tuple(20L, PayoutFailureReason.UNEXPECTED_ERROR),
                        tuple(20L, PayoutFailureReason.UNEXPECTED_ERROR));
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .allSatisfy(failure -> assertThat(failure.getDetail()).startsWith("draft 처리 중 예외: "));

        // then: 같은 회차의 판매자 10, 다음 회차의 판매자 30은 정산되고, 결과 검증도 통과함
        // 시스템 몫에는 판매자 20의 수수료(600)가 들어가지 않음 (판매자 몫과 함께 롤백됨)
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(10L, 9_700L),
                        tuple(30L, 970L),
                        tuple(null, 330L));
    }

    @Test
    void draft에서_실패한_판매자는_다음_배치에서_다시_처리되어_정산된다() {
        // given: 1차 배치에서 판매자 20의 draft 저장이 실패함 (UNEXPECTED_ERROR는 잠그지 않음)
        long orderItem20 = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItem20, 20L, 19_400L, TO.minusDays(1));
        insertCandidate(PayoutEventType.SALE_FEE, orderItem20, 20L, 600L, TO.minusDays(1));
        Long firstSnapshotId = createSnapshot(FROM, TO);
        payoutService.validate(firstSnapshotId);
        blockSellerDraft(firstSnapshotId, 20L);
        draftAll(firstSnapshotId, LIMIT);
        unblockSellerDraft(firstSnapshotId, 20L);
        payoutService.verify(firstSnapshotId);
        payoutService.commit(firstSnapshotId);

        // when: 오류가 사라진 뒤 2차 배치
        Long secondSnapshotId = settleAll(FROM, TO);

        // then: 1차는 실패 기록만 있고 정산 없음, 2차에서 다시 처리되어 정산됨
        assertThat(failureRepository.findBySnapshotId(firstSnapshotId))
                .extracting(PayoutFailure::getReason)
                .containsOnly(PayoutFailureReason.UNEXPECTED_ERROR);
        assertThat(payoutRepository.findBySnapshotId(firstSnapshotId)).isEmpty();
        assertThat(failureRepository.findBySnapshotId(secondSnapshotId)).isEmpty();
        assertThat(payoutRepository.findBySnapshotId(secondSnapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(20L, 19_400L), tuple(null, 600L));
    }

    // ---------- 대기 건너뛰기 실행 (#224) ----------

    @Test
    void 대기_건너뛰기_실행은_15일이_안_지난_후보도_정산한다() throws Exception {
        // given: 방금 생긴 후보 (평소 실행이면 15일 뒤에야 대상)
        insertSaleAmount(10L, 1_000L, LocalDateTime.now());

        // when
        JobExecution execution = payoutJobRunner.runSkippingWait();

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(execution)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(10L, 1_000L));
    }

    @Test
    void 평소_실행은_15일이_안_지난_후보를_정산하지_않는다() throws Exception {
        // given: 위 테스트와 같은 후보. 건너뛰기 실행만 차이를 만든다는 것을 보이기 위한 짝
        insertSaleAmount(10L, 1_000L, LocalDateTime.now());

        // when
        JobExecution execution = payoutJobRunner.run();

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(payoutRepository.count()).isZero();
    }

    @Test
    void 대기_건너뛰기로_연속_실행해도_이중_정산되지_않는다() throws Exception {
        // given: 판매자 10의 후보를 한 번 정산한 뒤, 판매자 20의 후보가 새로 생김
        insertSaleAmount(10L, 1_000L, LocalDateTime.now());
        JobExecution first = payoutJobRunner.runSkippingWait();
        insertSaleAmount(20L, 2_000L, LocalDateTime.now());

        // when: 같은 날 다시 실행 (두 번째 실행은 첫 정산이 있어 하한도 걸림)
        JobExecution second = payoutJobRunner.runSkippingWait();

        // then: 판매자 10은 첫 실행에서만, 판매자 20은 두 번째 실행에서만 정산됨
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertSoftly(softly -> {
            softly.assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(first)))
                    .extracting(Payout::getPayeeId, Payout::getAmount)
                    .containsExactly(tuple(10L, 1_000L));
            softly.assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(second)))
                    .extracting(Payout::getPayeeId, Payout::getAmount)
                    .containsExactly(tuple(20L, 2_000L));
        });
    }

    // ---------- 도우미: 단계 실행 ----------

    // 서비스 직접 호출용 스냅샷 생성 (Job 실행 ID는 테스트마다 새로 발급)
    private Long createSnapshot(LocalDateTime from, LocalDateTime to) {
        return payoutService.createSnapshot(JOB_EXECUTION_ID_SEQ.incrementAndGet(), from, to);
    }

    // draftStep처럼 0이 나올 때까지 회차 반복
    private void draftAll(Long snapshotId, int limit) {
        while (payoutService.draft(snapshotId, limit).sellerCount() > 0) {
            // 회차 반복
        }
    }

    // 스냅샷 → 입력 검증 → draft → 검증 → 확정까지 실행하고 스냅샷 ID 반환 (발행은 확인 대상이 아니라 생략)
    // 입력 검증도 실제 Job처럼 실행해, 금액 검사(#195)와 주문 잠금이 다음 배치에 이어지는 흐름을 확인할 수 있게 함
    private Long settleAll(LocalDateTime from, LocalDateTime to) {
        Long snapshotId = createSnapshot(from, to);
        payoutService.validate(snapshotId);
        draftAll(snapshotId, LIMIT);
        payoutService.verify(snapshotId);
        payoutService.commit(snapshotId);
        return snapshotId;
    }

    // Job 실행 1회 = 스냅샷 1건이라, 실행 ID로 그 실행이 만든 스냅샷을 찾음 (JPA 조회)
    private Long snapshotIdOf(JobExecution execution) {
        for (PayoutSnapshot snapshot : snapshotRepository.findAll()) {
            if (snapshot.getJobExecutionId().equals(execution.getId())) {
                return snapshot.getId();
            }
        }
        throw new IllegalStateException("실행에 해당하는 스냅샷이 없습니다. executionId=" + execution.getId());
    }

    // ---------- 도우미: 데이터 준비 (JDBC) ----------

    // 후보의 주문에 지갑 차감 기록을 넣음 (없으면 validateStep이 DEDUCTION_MISSING으로 빼서 정산되지 않음, #194)
    // 같은 주문에 후보가 여러 건(대금·수수료·배송비·조정)이라 주문당 1건만 넣음 (UK order_id)
    // 금액은 넉넉한 고정값: 초과 청구 검사(#195)는 "후보 합계 ≤ 차감액"이라 실제 합계로 맞추지 않아도 통과함
    private void givenDeduction(long orderId) {
        givenDeduction(orderId, 1_000_000L);
    }

    // 차감액을 정해서 넣음 (금액 검사 테스트용). 후보보다 먼저 불러야 함: 이미 있으면 위 고정값 도우미가 덮어쓰지 않고 건너뜀
    private void givenDeduction(long orderId, long amount) {
        if (payoutWalletDeductionRepository.existsByOrderId(orderId)) {
            return;
        }
        payoutWalletDeductionRepository.save(
                PayoutWalletDeduction.create(orderId, 1L, amount, LocalDateTime.now()));
    }

    // 판매 대금 후보 1건 (주문항목 ID는 자동 발급)
    private void insertSaleAmount(Long sellerId, Long amount, LocalDateTime createdAt) {
        insertCandidate(PayoutEventType.SALE_AMOUNT, nextOrderItemId(), sellerId, amount, createdAt);
    }

    // 정산 후보를 원하는 created_at으로 직접 저장 (+ 그 주문의 지갑 차감 기록)
    // 이 테스트는 상품 하나 = 주문 하나로 단순화해서 주문 ID에 주문항목 ID와 같은 값을 씀
    private void insertCandidate(PayoutEventType eventType, long orderItemId, Long sellerId, Long amount,
                                 LocalDateTime createdAt) {
        insertCandidateWithoutDeduction(eventType, orderItemId, sellerId, amount, createdAt);
        givenDeduction(orderItemId);
    }

    // 지갑 차감 기록 없이 후보만 저장 (차감 기록이 없는 주문을 만들 때, #194)
    private void insertCandidateWithoutDeduction(PayoutEventType eventType, long orderItemId, Long sellerId, Long amount,
                                                 LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                eventType.name(), orderItemId, orderItemId, sellerId, amount, createdAt, createdAt);
    }

    // 주문 하나에 주문상품이 여럿인 경우의 후보 (주문 ID와 주문상품 ID를 따로 받음, 지갑 차감 기록은 넣지 않음)
    // 주문 ID도 nextOrderItemId()로 받아야 cleanUp의 order_id 범위로 지워짐
    private void insertOrderCandidate(PayoutEventType eventType, long orderId, long orderItemId, Long sellerId, Long amount,
                                      LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                eventType.name(), orderId, orderItemId, sellerId, amount, createdAt, createdAt);
    }

    // 배송비 후보를 원하는 created_at으로 직접 저장 (+ 그 주문의 지갑 차감 기록)
    // 배송비는 판매자 묶음 단위라 order_item_id가 NULL
    private void insertShippingFee(long orderId, Long sellerId, Long amount, LocalDateTime createdAt) {
        insertShippingFeeWithoutDeduction(orderId, sellerId, amount, createdAt);
        givenDeduction(orderId);
    }

    // 지갑 차감 기록 없이 배송비 후보만 저장 (#194)
    private void insertShippingFeeWithoutDeduction(long orderId, Long sellerId, Long amount, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) " +
                        "VALUES (?, ?, NULL, ?, ?, ?, ?)",
                PayoutEventType.SALE_SHIPPING_FEE.name(), orderId, sellerId, amount, createdAt, createdAt);
    }

    // 관리자 수동 조정 후보를 원하는 created_at으로 직접 저장 (관리자 ID·사유는 테스트용 고정값)
    // 주문 ID는 insertCandidate와 같은 규칙 (주문항목 ID와 같은 값)
    private void insertAdjustment(PayoutEventType eventType, long orderItemId, Long sellerId, Long amount,
                                  long adjustmentId, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items " +
                        "(event_type, order_id, order_item_id, seller_id, amount, adjustment_id, admin_id, reason, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventType.name(), orderItemId, orderItemId, sellerId, amount, adjustmentId, 99L, "테스트 조정",
                createdAt, createdAt);
        givenDeduction(orderItemId);
    }

    // 검증 실패 상황 만들기: draft가 끝난 스냅샷 목록에, draft되지 않은 후보 1건을 끼워 넣음
    // (정상 흐름에서는 생기지 않는 상태라 서비스로 만들 수 없어 SQL로 직접 넣음)
    private void insertMissedSnapshotItem(Long snapshotId, Long sellerId, Long amount) {
        insertMissedSnapshotItem(snapshotId, sellerId, amount, LocalDateTime.now().minusDays(20));
    }

    // 끼워 넣을 후보의 생성 시각을 정해서 넣음
    // 다음 스냅샷에 이 후보가 들어가는지가 결과에 영향을 주는 테스트용 (위 도우미의 "오늘 − 20일"은 실행 날짜에 따라 고정 기간 안팎이 바뀜)
    private void insertMissedSnapshotItem(Long snapshotId, Long sellerId, Long amount, LocalDateTime createdAt) {
        long orderItemId = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemId, sellerId, amount, createdAt);
        Long candidateId = candidateIdsOf(orderItemId).get(0);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO payout_snapshot_item (snapshot_id, candidate_id, amount, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                snapshotId, candidateId, amount, now, now);
    }

    // draft 저장 중 DB 오류 재현 (#90): 그 스냅샷·판매자의 빈 draft를 미리 넣어 두면, draft 저장 때 UK(snapshot_id, seller_id) 위반이 남
    // 목(mock) 없이 실제 트랜잭션·DB 예외로 "한 판매자만 실패"를 만들려고 씀 (정상 흐름에서는 생기지 않는 상태라 SQL로 넣음)
    private void blockSellerDraft(Long snapshotId, Long sellerId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO payout_seller_draft (snapshot_id, seller_id, amount, created_at, updated_at) VALUES (?, ?, 0, ?, ?)",
                snapshotId, sellerId, now, now);
    }

    // 오류가 사라진 상황 재현: 막아 둔 빈 draft를 지움
    // 실제 오류 상황(롤백되어 아무것도 안 남음)과 같은 상태로 되돌려, 이후 검증·확정이 실제와 같은 데이터를 보게 함
    private void unblockSellerDraft(Long snapshotId, Long sellerId) {
        jdbcTemplate.update("DELETE FROM payout_seller_draft WHERE snapshot_id = ? AND seller_id = ?", snapshotId, sellerId);
    }

    // ---------- 도우미: 결과 검증 불일치 재현 (#290) ----------
    // 정상 흐름에서는 생기지 않는 상태(우리 코드 버그)라 서비스로 만들 수 없어 draft 뒤에 SQL로 바꿔 재현함

    // draft가 금액을 다르게 옮긴 상황: draft 쪽 대신 스냅샷 쪽 금액을 바꿔 재현 (draft 금액을 바꾸면 판매자 합계 검사에 먼저 걸림)
    private void changeSnapshotAmount(Long snapshotId, Long candidateId, long amount) {
        jdbcTemplate.update("UPDATE payout_snapshot_item SET amount = ? WHERE snapshot_id = ? AND candidate_id = ?",
                amount, snapshotId, candidateId);
    }

    // 판매자 합계(헤더)만 틀린 상황 (draft를 만드는 코드가 망가진 상황)
    private void changeSellerDraftAmount(Long snapshotId, Long sellerId, long amount) {
        jdbcTemplate.update("UPDATE payout_seller_draft SET amount = ? WHERE snapshot_id = ? AND seller_id = ?",
                amount, snapshotId, sellerId);
    }

    // 스냅샷에 없는 후보가 시스템 몫 draft에 들어간 상황
    private void insertSystemDraftItem(Long snapshotId, Long candidateId) {
        PayoutCandidateItem candidate = candidateRepository.findById(candidateId).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO payout_system_draft_item (snapshot_id, candidate_id, event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshotId, candidateId, candidate.getEventType().name(), candidate.getOrderId(), candidate.getOrderItemId(),
                candidate.getSellerId(), candidate.getAmount(), now, now);
    }

    // 판매자 몫 항목 하나가 다른 판매자의 헤더 아래로 들어간 상황
    // 두 헤더 금액은 항목 합계로 다시 맞춰, 판매자 합계 검사가 아니라 항목 단위 검사로 잡히는지 확인할 수 있게 함
    private void moveSellerDraftItem(Long snapshotId, Long candidateId, Long fromSellerId, Long toSellerId) {
        jdbcTemplate.update(
                "UPDATE payout_seller_draft_item SET seller_draft_id = " +
                        "(SELECT id FROM payout_seller_draft WHERE snapshot_id = ? AND seller_id = ?) " +
                        "WHERE snapshot_id = ? AND candidate_id = ?",
                snapshotId, toSellerId, snapshotId, candidateId);
        for (Long sellerId : List.of(fromSellerId, toSellerId)) {
            jdbcTemplate.update(
                    "UPDATE payout_seller_draft d SET amount = " +
                            "(SELECT COALESCE(SUM(i.amount), 0) FROM payout_seller_draft_item i WHERE i.seller_draft_id = d.id) " +
                            "WHERE d.snapshot_id = ? AND d.seller_id = ?",
                    snapshotId, sellerId);
        }
    }

    // 판매자 헤더(headerSnapshotId) 아래에 다른 스냅샷 번호(itemSnapshotId)를 가진 항목이 들어간 상황, 헤더 금액은 항목 합계로 맞춤
    private void insertSellerDraftItemUnderHeader(Long headerSnapshotId, Long sellerId, Long itemSnapshotId, Long candidateId) {
        PayoutCandidateItem candidate = candidateRepository.findById(candidateId).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO payout_seller_draft_item (snapshot_id, seller_draft_id, candidate_id, event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) " +
                        "VALUES (?, (SELECT id FROM payout_seller_draft WHERE snapshot_id = ? AND seller_id = ?), ?, ?, ?, ?, ?, ?, ?, ?)",
                itemSnapshotId, headerSnapshotId, sellerId, candidateId, candidate.getEventType().name(),
                candidate.getOrderId(), candidate.getOrderItemId(), candidate.getSellerId(), candidate.getAmount(), now, now);
        jdbcTemplate.update(
                "UPDATE payout_seller_draft d SET amount = " +
                        "(SELECT COALESCE(SUM(i.amount), 0) FROM payout_seller_draft_item i WHERE i.seller_draft_id = d.id) " +
                        "WHERE d.snapshot_id = ? AND d.seller_id = ?",
                headerSnapshotId, sellerId);
    }

    // draft된 후보에 실패 기록이 있는 상황 (draft 대상 조회 조건이 실패 후보를 거르지 못한 것과 같은 상태)
    private void insertFailure(Long snapshotId, Long candidateId, long orderId, Long sellerId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO payout_failure (snapshot_id, candidate_id, order_id, order_item_id, seller_id, reason, detail, created_at, updated_at) " +
                        "VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?)",
                snapshotId, candidateId, orderId, sellerId, PayoutFailureReason.DEDUCTION_MISSING.name(), "테스트", now, now);
    }

    // 주문·판매자·타입으로 정산후보 id 하나를 찾음 (JPA 조회)
    private Long candidateIdOf(long orderId, Long sellerId, PayoutEventType eventType) {
        for (PayoutCandidateItem candidate : candidateRepository.findAllByOrderId(orderId)) {
            if (candidate.getSellerId().equals(sellerId) && candidate.getEventType() == eventType) {
                return candidate.getId();
            }
        }
        throw new IllegalStateException("정산후보가 없습니다. orderId=" + orderId + ", sellerId=" + sellerId);
    }

    private long nextOrderItemId() {
        return ORDER_ITEM_ID_SEQ.incrementAndGet();
    }

    // 주문의 정산후보 id 목록 (JPA 조회)
    // 주문 ID로 찾는 이유: 배송비 후보는 order_item_id가 NULL이라 주문항목 ID로는 빠짐
    // 이 테스트는 주문 ID = 주문항목 ID라서 주문항목 번호를 그대로 넘겨도 됨
    private List<Long> candidateIdsOf(long orderId) {
        List<Long> ids = new ArrayList<>();
        for (PayoutCandidateItem candidate : candidateRepository.findAllByOrderId(orderId)) {
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
        // 실패 기록은 스냅샷·후보를 가리키는 FK가 있어 둘보다 먼저 지움
        jdbcTemplate.update("DELETE FROM payout_failure");
        jdbcTemplate.update("DELETE FROM payout_snapshot_item");
        jdbcTemplate.update("DELETE FROM payout_snapshot");
        // 주문 ID로 지우는 이유: 배송비 행은 order_item_id가 NULL이라 주문항목 범위로는 지워지지 않음
        // (이 클래스는 주문 ID에 주문항목 번호를 그대로 쓰므로 같은 범위로 지울 수 있음)
        jdbcTemplate.update("DELETE FROM payout_candidate_items WHERE order_id > ? AND order_id <= ?",
                ORDER_ITEM_ID_START, ORDER_ITEM_ID_END);
        // 지갑 차감 기록도 이 클래스 번호대만 지움 (후보를 넣을 때 같은 주문 ID로 함께 넣음)
        jdbcTemplate.update("DELETE FROM payout_wallet_deduction WHERE order_id > ? AND order_id <= ?",
                ORDER_ITEM_ID_START, ORDER_ITEM_ID_END);
    }
}
