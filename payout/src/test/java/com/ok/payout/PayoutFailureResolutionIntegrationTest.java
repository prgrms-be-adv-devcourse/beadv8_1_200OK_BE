package com.ok.payout;

import com.ok.common.exception.RestApiException;
import com.ok.payout.adapter.in.batch.PayoutJobRunner;
import com.ok.payout.adapter.out.persistence.PayoutCandidateItemJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutFailureResolutionJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutSellerDraftJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutSnapshotItemJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutSnapshotJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutWalletDeductionJpaRepository;
import com.ok.payout.application.port.in.PayoutFailureResolutionUseCase;
import com.ok.payout.application.port.in.PayoutOrderResolutionResult;
import com.ok.payout.application.port.in.PayoutOrderResolveCommand;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import com.ok.payout.application.service.PayoutService;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutFailureResolution;
import com.ok.payout.domain.PayoutResolutionMethod;
import com.ok.payout.domain.PayoutSellerDraft;
import com.ok.payout.domain.PayoutSellerDraftItem;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.domain.PayoutSnapshotItem;
import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.payout.domain.type.PayoutRunType;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 통합 테스트: 잠기거나 멈춘 주문의 해결 "다시 처리" (#568)와 해결 기록 조회 (#571)
// - 실행(COMPLETED)은 실제 정산 Job(PayoutJobRunner.runScheduled)으로 만든다
// - 멈춘 주문은 지갑 결제 기록 없는 후보로 실제 Job이 스스로 "결제 기록 없음"으로 실패하게 해서 만든다 (실제 흐름 그대로)
// - 잠긴 주문·사유가 섞인 이력은 실행을 먼저 만들고, 그 스냅샷에 실패 기록을 SQL로 넣는다 (#549 PayoutFailureRetryIntegrationTest와 같은 방식)
// - 해결은 실제 서비스(입력 포트)와 실제 HTTP 요청(MockMvc + 로그인 토큰)으로 한다
// - 결과 확인은 포트·JPA 리포지토리로 한다. JDBC는 데이터 준비·정리에만 씀
class PayoutFailureResolutionIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 주문 ID 번호대 (다른 통합 테스트와 겹치지 않게 5_200_000~5_300_000)
    private static final long ORDER_ID_START = 5_200_000L;
    private static final long ORDER_ID_END = 5_300_000L;
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(ORDER_ID_START);
    // 서비스로 직접 만드는 스냅샷의 가짜 실행 ID (실행 기록이 없어 실패 횟수에 세지 않음)
    private static final AtomicLong SNAPSHOT_EXECUTION_ID_SEQ = new AtomicLong(910_000_000L);
    // 주문항목 ID 번호대: 주문 ID와 겹치지 않게 따로 발급
    private static final AtomicLong ORDER_ITEM_ID_SEQ = new AtomicLong(16_000_000L);

    private static final long ADMIN_ID = 1L;
    private static final long SELLER_ID = 10L;
    private static final String MEMO = "차감액을 바로잡음";
    // 오류 응답 type 끝부분 (모듈 이름 + 에러 코드 이름을 소문자·하이픈으로 바꾼 값)
    private static final String NOTHING_TO_RESOLVE_TYPE = "/payout/nothing-to-resolve";

    @Autowired
    private PayoutFailureResolutionUseCase resolutionUseCase;

    @Autowired
    private PayoutQueryRepository payoutQueryRepository;

    @Autowired
    private PayoutFailureResolutionJpaRepository resolutionRepository;

    @Autowired
    private PayoutJobRunner payoutJobRunner;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private PayoutSnapshotJpaRepository snapshotRepository;

    @Autowired
    private PayoutSnapshotItemJpaRepository snapshotItemRepository;

    @Autowired
    private PayoutSellerDraftJpaRepository sellerDraftRepository;

    @Autowired
    private PayoutJpaRepository payoutRepository;

    @Autowired
    private PayoutCandidateItemJpaRepository candidateRepository;

    @Autowired
    private PayoutWalletDeductionJpaRepository walletDeductionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TestAccessTokens tokens;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        cleanUp();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }


    // ---------- 해결하면 다시 정산 ----------

    @Test
    void 결과_검증_불일치로_잠긴_주문을_해결하면_다음_실행에서_정산된다() throws Exception {
        // given: 20일 전 후보(대기 지남)가 결과 검증 불일치로 잠김
        //   첫 실행이 기준 정산을 만들어 조회 하한(약 18일 전)이 생기게 함 → 20일 전 후보는 기간 밖
        givenBaselineSettlement();
        Long firstRun = runCompleted();
        long order = nextOrderId();
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        long candidate = insertCandidate(order, createdAt);
        insertFailure(firstRun, candidate, order, PayoutFailureReason.VERIFY_MISMATCH);
        assertThat(payoutService.settlementFrom()).isAfter(createdAt);
        // 잠긴 동안의 실행에서는 정산되지 않음
        assertThat(payoutRepository.findBySnapshotId(runCompleted())).isEmpty();

        // when: 코드를 고쳤다고 보고 관리자가 해결
        resolve(order);
        Long nextRun = runCompleted();

        // then: 조회 기간 밖으로 밀렸어도 해결된 주문이라 다시 들어가 정산됨
        assertThat(payoutRepository.findBySnapshotId(nextRun))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(SELLER_ID, 1_000L));
    }

    @Test
    void 세번_실패해_멈춘_주문을_데이터를_고치고_해결하면_조회_기간_밖이어도_다음_실행에서_정산된다() throws Exception {
        // given: 30일 전 후보에 지갑 결제 기록이 없어 실제 Job 3번에서 "결제 기록 없음"으로 실패해 멈춤
        //   첫 실행이 기준 정산을 만들어 조회 하한(약 18일 전)이 생김 → 2번째부터는 기간 밖이라 #549 다시 넣기로만 들어감
        givenBaselineSettlement();
        long order = nextOrderId();
        LocalDateTime createdAt = LocalDateTime.now().minusDays(30);
        long candidate = insertCandidateWithoutDeduction(order, createdAt);
        runCompleted(3);
        assertThat(payoutService.settlementFrom()).isAfter(createdAt);
        assertThat(payoutQueryRepository.countFailedRunsByOrder()).containsEntry(order, 3L);
        assertThat(snapshotCandidateIds()).doesNotContain(candidate);

        // when: 결제 기록이 뒤늦게 들어와 데이터가 고쳐진 뒤 관리자가 해결
        givenDeduction(order);
        resolve(order);
        Long nextRun = runCompleted();

        // then: 해결로 횟수가 0이 되어 #549 다시 넣기에는 안 들지만, 해결된 주문이라 기간 밖에서 다시 들어가 정산됨
        assertThat(payoutRepository.findBySnapshotId(nextRun))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(SELLER_ID, 1_000L));
    }

    @Test
    void 해결_뒤_고치지_않아_다시_세번_실패하면_다시_멈춘다() throws Exception {
        // given: 결제 기록이 없어 3번 실패해 멈춘 주문을 고치지 않고 해결
        long order = nextOrderId();
        long candidate = insertCandidateWithoutDeduction(order, LocalDateTime.now().minusDays(30));
        runCompleted(3);
        resolve(order);

        // when: 해결 뒤 실제 Job에서 다시 2번 실패 → 아직 다시 검사, 3번째 실패 → 멈춤
        runCompleted(2);
        List<Long> afterTwo = snapshotCandidateIds();
        runCompleted(1);
        List<Long> afterThree = snapshotCandidateIds();

        // then: 횟수를 처음부터 세어 3번이면 다시 멈춤 (해결된 주문이라고 계속 다시 넣지 않음)
        assertThat(afterTwo).containsExactly(candidate);
        assertThat(afterThree).doesNotContain(candidate);
        assertThat(payoutQueryRepository.countFailedRunsByOrder()).containsEntry(order, 3L);
    }

    @Test
    void 잠그는_사유와_아닌_사유가_섞인_주문을_해결하면_모두_해결되어_횟수가_0이_된다() throws Exception {
        // given: 초과 청구 2번 + 결과 검증 불일치 1번 = 성공한 실행 3번 (잠김이면서 멈춤)
        List<Long> runs = runCompleted(3);
        long order = nextOrderId();
        long candidate = insertCandidate(order, LocalDateTime.now().minusDays(30));
        insertFailure(runs.get(0), candidate, order, PayoutFailureReason.AMOUNT_MISMATCH);
        insertFailure(runs.get(1), candidate, order, PayoutFailureReason.AMOUNT_MISMATCH);
        insertFailure(runs.get(2), candidate, order, PayoutFailureReason.VERIFY_MISMATCH);

        // 해결 서비스가 저장할 실패 기록은 id 오름차순 (동시 해결 때 데드락 대신 유니크 위반이 나게)
        assertThat(payoutQueryRepository.findUnresolvedFailuresByOrderId(order))
                .extracting(PayoutFailure::getId).isSorted().hasSize(3);

        // when
        PayoutOrderResolutionResult result = resolve(order);

        // then: 잠그는 사유만이 아니라 실패 기록 3건 전부 해결 → 잠김도 멈춤도 아니고 다음 스냅샷에 들어감
        assertThat(result.resolvedFailureCount()).isEqualTo(3);
        // 요청 1번에 생긴 3줄은 모두 같은 요청 번호 (#571 묶음 보기)
        assertThat(resolutionRepository.findAllByOrderId(order))
                .extracting(PayoutFailureResolution::getResolutionRequestId)
                .containsOnly(result.resolutionRequestId());
        assertThat(payoutQueryRepository.isOrderLocked(order)).isFalse();
        assertThat(payoutQueryRepository.countFailedRunsByOrder()).doesNotContainKey(order);
        assertThat(snapshotCandidateIds()).containsExactly(candidate);
    }

    @Test
    void 해결_뒤_새로_생긴_잠그는_실패는_다시_잠근다() throws Exception {
        // given: 수수료 불일치로 잠긴 주문을 해결
        List<Long> runs = runCompleted(2);
        long order = nextOrderId();
        long candidate = insertCandidate(order, LocalDateTime.now().minusDays(30));
        insertFailure(runs.get(0), candidate, order, PayoutFailureReason.FEE_MISMATCH);
        resolve(order);
        assertThat(payoutQueryRepository.isOrderLocked(order)).isFalse();

        // when: 고치지 않아 다시 검사에서 같은 실패가 새로 기록됨
        insertFailure(runs.get(1), candidate, order, PayoutFailureReason.FEE_MISMATCH);

        // then: 해결 기록은 실패 기록마다라 새 실패는 해결되지 않은 것 → 다시 잠기고 스냅샷에서 빠짐
        assertThat(payoutQueryRepository.isOrderLocked(order)).isTrue();
        assertThat(snapshotCandidateIds()).doesNotContain(candidate);
    }

    @Test
    void 해결된_주문이라도_상한_이후_후보뿐이면_다시_넣지_않는다() throws Exception {
        // given: 해결한 주문의 남은 후보가 스냅샷 상한(내일) 이후
        Long run = runCompleted();
        long order = nextOrderId();
        long candidate = insertCandidate(order, LocalDateTime.now().minusDays(30));
        insertFailure(run, candidate, order, PayoutFailureReason.VERIFY_MISMATCH);
        resolve(order);
        assertThat(snapshotCandidateIds()).contains(candidate);
        jdbcTemplate.update("UPDATE payout_candidate_items SET created_at = ? WHERE id = ?",
                LocalDateTime.now().plusDays(5), candidate);

        // when & then: 상한은 그대로라 들어가지 않음 (해결은 기간 하한만 풀어 줌)
        assertThat(snapshotCandidateIds()).doesNotContain(candidate);
    }

    @Test
    void 배치가_도는_중에_해결해도_그_실행에서_실패한_후보는_draft에_들어가지_않는다() throws Exception {
        // given: 같은 스냅샷에 두 주문. 한 주문의 후보가 그 스냅샷의 금액 검사에서 수수료 불일치로 실패한 상태
        long order = nextOrderId();
        long candidate = insertCandidate(order, LocalDateTime.now());
        long otherOrder = nextOrderId();
        long otherCandidate = insertCandidate(otherOrder, LocalDateTime.now());
        Long snapshotId = createSnapshotNow();
        assertThat(candidateIdsOf(snapshotId)).containsExactlyInAnyOrder(candidate, otherCandidate);
        insertFailure(snapshotId, candidate, order, PayoutFailureReason.FEE_MISMATCH);

        // when: 같은 실행의 draft 전에 관리자가 해결
        resolve(order);
        payoutService.draft(snapshotId, 100);

        // then: 한 실행 안의 제외는 해결과 무관 → 그 실행의 draft에는 실패 없는 다른 주문만 들어감 (해결한 주문은 다음 실행부터 다시 검사)
        List<Long> draftedOrderIds = new ArrayList<>();
        for (PayoutSellerDraft draft : sellerDraftRepository.findBySnapshotId(snapshotId)) {
            for (PayoutSellerDraftItem item : draft.getItems()) {
                draftedOrderIds.add(item.getOrderId());
            }
        }
        assertThat(draftedOrderIds).containsExactly(otherOrder);
    }

    @Test
    void 주문_하나의_실패_횟수는_전체_집계의_그_주문_값과_같다() throws Exception {
        // given: 해결해서 0번이 된 주문, 결제 기록이 없어 실제 Job에서 2번 실패한 주문, 실패 없는 주문
        //   잠긴 주문 준비가 실행을 한 번 돌리므로 먼저 함 (뒤에 하면 2번 실패한 주문이 3번째로 실패함)
        long resolvedOrder = givenLockedOrder();
        resolve(resolvedOrder);
        long twiceOrder = nextOrderId();
        insertCandidateWithoutDeduction(twiceOrder, LocalDateTime.now().minusDays(30));
        runCompleted(2);
        long cleanOrder = nextOrderId();
        insertCandidate(cleanOrder, LocalDateTime.now().minusDays(30));

        // when & then: 해결 API의 멈춤 판정(단건)과 스냅샷의 멈춤 판정(전체)이 같은 기준으로 셈
        Map<Long, Long> all = payoutQueryRepository.countFailedRunsByOrder();
        for (long order : List.of(twiceOrder, cleanOrder, resolvedOrder)) {
            assertThat(payoutQueryRepository.countFailedRunsOfOrder(order)).isEqualTo(all.getOrDefault(order, 0L));
        }
        assertThat(payoutQueryRepository.countFailedRunsOfOrder(twiceOrder)).isEqualTo(2L);
    }

    // ---------- 해결할 것이 없을 때 ----------

    @Test
    void 해결할_것이_없는_주문은_409이고_해결_기록을_남기지_않는다() throws Exception {
        // given: 실패 없는 주문, 2번 실패해 다시 검사 중인 주문, 실패로 끝난 실행에서만 실패한 주문
        long cleanOrder = nextOrderId();
        insertCandidate(cleanOrder, LocalDateTime.now().minusDays(30));
        long twiceOrder = nextOrderId();
        insertCandidateWithoutDeduction(twiceOrder, LocalDateTime.now().minusDays(30));
        runCompleted(2);
        Long failedRun = runFailed();
        long failedRunOrder = nextOrderId();
        long failedRunCandidate = insertCandidate(failedRunOrder, LocalDateTime.now().minusDays(30));
        insertFailure(failedRun, failedRunCandidate, failedRunOrder, PayoutFailureReason.DEDUCTION_MISSING);
        assertThat(payoutQueryRepository.countFailedRunsByOrder()).containsEntry(twiceOrder, 2L);

        // when & then
        for (long order : List.of(cleanOrder, twiceOrder, failedRunOrder)) {
            assertThatThrownBy(() -> resolve(order))
                    .isInstanceOfSatisfying(RestApiException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.NOTHING_TO_RESOLVE));
            assertThat(resolutionRepository.findAllByOrderId(order)).isEmpty();
        }
    }

    @Test
    void 이미_해결한_주문을_다시_해결하면_409이고_해결_기록은_처음_것만_남는다() throws Exception {
        // given
        long order = givenLockedOrder();
        resolve(order);

        // when & then
        assertThatThrownBy(() -> resolve(order))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.NOTHING_TO_RESOLVE));
        assertThat(resolutionRepository.findAllByOrderId(order)).hasSize(1);
    }

    @Test
    void 같은_주문을_동시에_두번_해결하면_하나만_해결되고_하나는_409다() throws Exception {
        // given: 결제 기록이 없어 실제 Job 3번에서 실패해 멈춘 주문 (실패 기록 3건)
        long order = nextOrderId();
        insertCandidateWithoutDeduction(order, LocalDateTime.now().minusDays(30));
        runCompleted(3);

        // when: 두 요청을 같은 순간에 출발시킴 (둘 다 "해결할 것 있음"을 통과하면 두 번째가 유니크에 걸림)
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> request = () -> {
            start.await();
            try {
                return resolve(order);
            } catch (RestApiException e) {
                return e.getErrorCode();
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Object> outcomes = new ArrayList<>();
        try {
            List<Future<Object>> futures = List.of(executor.submit(request), executor.submit(request));
            start.countDown();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            // 시간 초과로 끝나도 스레드가 늦게 저장해 다음 테스트 정리를 깨지 않게 멈춤
            executor.shutdownNow();
        }

        // then: 하나는 해결(3건), 하나는 409. 해결 기록은 실패 기록마다 1건만 (500이나 중복 없음)
        assertThat(outcomes).filteredOn(PayoutOrderResolutionResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(PayoutErrorCode.NOTHING_TO_RESOLVE::equals).hasSize(1);
        assertThat(resolutionRepository.findAllByOrderId(order)).hasSize(3);
    }

    @Test
    void 이미_해결된_실패_기록을_다시_저장하면_DB가_중복_키_오류_번호로_거절한다() throws Exception {
        // given: 해결 서비스가 409로 바꾸는 근거(DB 드라이버 예외의 오류 번호 1062)가 실제 MySQL에서 나오는지 확인하려는 것
        //   동시 요청 테스트는 두 요청이 겹치는 순간을 매번 만들 수 없어, 유니크 경로를 여기서 직접 확인함
        long order = givenLockedOrder();
        List<PayoutFailure> failures = payoutQueryRepository.findUnresolvedFailuresByOrderId(order);
        resolve(order);
        List<PayoutFailureResolution> duplicate = List.of(
                PayoutFailureResolution.of(failures.get(0), "test-request", PayoutResolutionMethod.REPROCESS, ADMIN_ID, MEMO));

        // when & then
        assertThatThrownBy(() -> resolutionRepository.saveAll(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause()
                .satisfies(cause -> assertThat(((SQLException) cause).getErrorCode()).isEqualTo(1062));
    }

    // ---------- 해결 기록 ----------

    @Test
    void 해결_기록에_실패_기록마다_누가_어떻게_메모가_남는다() throws Exception {
        // given: 실패 기록 2건 (결제 기록 없음, 결과 검증 불일치)
        List<Long> runs = runCompleted(2);
        long order = nextOrderId();
        long candidate = insertCandidate(order, LocalDateTime.now().minusDays(30));
        insertFailure(runs.get(0), candidate, order, PayoutFailureReason.DEDUCTION_MISSING);
        insertFailure(runs.get(1), candidate, order, PayoutFailureReason.VERIFY_MISMATCH);

        // when
        PayoutOrderResolutionResult result = resolve(order);

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.orderId()).isEqualTo(order);
            softly.assertThat(result.resolvedFailureCount()).isEqualTo(2);
            softly.assertThat(result.method()).isEqualTo(PayoutResolutionMethod.REPROCESS.name());
            softly.assertThat(result.adminId()).isEqualTo(ADMIN_ID);
            softly.assertThat(result.memo()).isEqualTo(MEMO);
            softly.assertThat(result.resolvedAt()).isNotNull();
        });
        assertThat(resolutionRepository.findAllByOrderId(order))
                .extracting(PayoutFailureResolution::getMethod, PayoutFailureResolution::getAdminId, PayoutFailureResolution::getMemo)
                .containsOnly(tuple(PayoutResolutionMethod.REPROCESS, ADMIN_ID, MEMO));
    }

    // ---------- API ----------

    @Test
    void 관리자가_해결_API를_부르면_201과_해결_기록을_돌려준다() throws Exception {
        // given
        long order = givenLockedOrder();

        // when & then: 메모 앞뒤 공백은 지워져 저장됨
        mockMvc.perform(resolveRequest(order, "{\"memo\": \"  " + MEMO + "  \"}")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isCreated())                                          // 201
                .andExpect(jsonPath("$.orderId").value(order))
                .andExpect(jsonPath("$.resolutionRequestId").isNotEmpty())
                .andExpect(jsonPath("$.resolvedFailureCount").value(1))
                .andExpect(jsonPath("$.method").value("REPROCESS"))
                .andExpect(jsonPath("$.adminId").value(ADMIN_ID))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.memo").value(MEMO));
    }

    @Test
    void 관리자가_아니면_403이고_해결_기록을_남기지_않는다() throws Exception {
        // given
        long order = givenLockedOrder();

        // when & then
        mockMvc.perform(resolveRequest(order, "{\"memo\": \"" + MEMO + "\"}")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(7L, "BUYER", "SELLER")))
                .andExpect(status().isForbidden());                                       // 403
        assertThat(resolutionRepository.findAllByOrderId(order)).isEmpty();
    }

    @Test
    void 메모가_공백뿐이거나_255자를_넘으면_422다() throws Exception {
        // given
        long order = givenLockedOrder();
        String admin = tokens.bearer(ADMIN_ID, "ADMIN");

        // when & then
        mockMvc.perform(resolveRequest(order, "{\"memo\": \"   \"}").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isUnprocessableEntity());                             // 422
        mockMvc.perform(resolveRequest(order, "{\"memo\": \"" + "가".repeat(256) + "\"}").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isUnprocessableEntity());                             // 422
        assertThat(resolutionRepository.findAllByOrderId(order)).isEmpty();

        // 255자는 통과 (경계)
        mockMvc.perform(resolveRequest(order, "{\"memo\": \"" + "가".repeat(255) + "\"}").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isCreated());
    }

    @Test
    void 해결할_것이_없는_주문에_해결_API를_부르면_409다() throws Exception {
        // given: 실패 없는 주문
        long order = nextOrderId();
        insertCandidate(order, LocalDateTime.now().minusDays(30));

        // when & then
        mockMvc.perform(resolveRequest(order, "{\"memo\": \"" + MEMO + "\"}")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isConflict())                                         // 409
                .andExpect(jsonPath("$.type").value(endsWith(NOTHING_TO_RESOLVE_TYPE)));
    }

    // ---------- 해결 기록 조회 (#571) ----------

    @Test
    void 해결_기록을_실패_기록마다_1줄씩_최신순으로_정산후보_정보와_함께_준다() throws Exception {
        // given: 상품 1개(대금·수수료)와 배송비가 있는 주문이 결과 검증 불일치로 잠김 → 실패 기록 3건을 해결
        Long run = runCompleted();
        long order = nextOrderId();
        long orderItem = ORDER_ITEM_ID_SEQ.incrementAndGet();
        long amountCandidate = insertCandidateOf(order, PayoutEventType.SALE_AMOUNT, orderItem, 10_000L);
        long feeCandidate = insertCandidateOf(order, PayoutEventType.SALE_FEE, orderItem, 1_000L);
        long shippingCandidate = insertCandidateOf(order, PayoutEventType.SALE_SHIPPING_FEE, null, 3_000L);
        insertFailureOf(run, amountCandidate, order, orderItem, "대금 상세");
        insertFailureOf(run, feeCandidate, order, orderItem, "수수료 상세");
        insertFailureOf(run, shippingCandidate, order, null, "배송비 상세");
        PayoutOrderResolutionResult resolved = resolve(order);

        // when & then: 해결 기록 ID 역순(나중에 저장한 배송비 줄이 먼저), 3줄 모두 같은 요청 번호
        mockMvc.perform(resolutionsRequest(order).header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].eventType").value(contains("SALE_SHIPPING_FEE", "SALE_FEE", "SALE_AMOUNT")))
                .andExpect(jsonPath("$.content[*].amount").value(contains(3_000, 1_000, 10_000)))
                .andExpect(jsonPath("$.content[*].resolutionRequestId").value(everyItem(is(resolved.resolutionRequestId()))))
                // 배송비 줄의 모든 칸
                .andExpect(jsonPath("$.content[0].resolutionId").isNumber())
                .andExpect(jsonPath("$.content[0].failureId").isNumber())
                .andExpect(jsonPath("$.content[0].orderId").value(order))
                .andExpect(jsonPath("$.content[0].orderItemId").value(nullValue()))       // 배송비는 주문상품 ID 없음
                .andExpect(jsonPath("$.content[0].sellerId").value(SELLER_ID))
                .andExpect(jsonPath("$.content[0].reason").value("VERIFY_MISMATCH"))
                .andExpect(jsonPath("$.content[0].category").value("SYSTEM"))
                .andExpect(jsonPath("$.content[0].detail").value("배송비 상세"))
                .andExpect(jsonPath("$.content[0].failedAt").isNotEmpty())
                .andExpect(jsonPath("$.content[0].method").value("REPROCESS"))
                .andExpect(jsonPath("$.content[0].adminId").value(ADMIN_ID))
                .andExpect(jsonPath("$.content[0].resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.content[0].memo").value(MEMO))
                // 상품 줄은 주문상품 ID가 있음
                .andExpect(jsonPath("$.content[2].orderItemId").value(orderItem))
                .andExpect(jsonPath("$.content[2].detail").value("대금 상세"));
    }

    @Test
    void 두_번_해결한_주문은_나중_해결이_먼저_나오고_페이지를_나눌_수_있다() throws Exception {
        // given: 잠김 → 해결 → 다시 잠김 → 다시 해결 (해결 요청 2번, 줄 2개)
        List<Long> runs = runCompleted(2);
        long order = nextOrderId();
        long candidate = insertCandidate(order, LocalDateTime.now().minusDays(30));
        insertFailure(runs.get(0), candidate, order, PayoutFailureReason.VERIFY_MISMATCH);
        PayoutOrderResolutionResult first = resolve(order);
        insertFailure(runs.get(1), candidate, order, PayoutFailureReason.VERIFY_MISMATCH);
        PayoutOrderResolutionResult second = resolve(order);
        String admin = tokens.bearer(ADMIN_ID, "ADMIN");

        // when & then: 한 페이지에 1줄씩, 첫 페이지는 나중 해결
        mockMvc.perform(resolutionsRequest(order).param("page", "0").param("size", "1").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].resolutionRequestId").value(second.resolutionRequestId()));
        mockMvc.perform(resolutionsRequest(order).param("page", "1").param("size", "1").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].resolutionRequestId").value(first.resolutionRequestId()));
        assertThat(first.resolutionRequestId()).isNotEqualTo(second.resolutionRequestId());
    }

    @Test
    void 해결_기록이_없는_주문은_빈_목록이다() throws Exception {
        // given: 잠겼지만 아직 해결하지 않은 주문
        long order = givenLockedOrder();

        // when & then
        mockMvc.perform(resolutionsRequest(order).header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void 해결_기록_조회는_토큰이_없으면_401_관리자가_아니면_403이다() throws Exception {
        // given
        long order = givenLockedOrder();
        resolve(order);

        // when & then
        mockMvc.perform(resolutionsRequest(order))
                .andExpect(status().isUnauthorized());                                    // 401
        mockMvc.perform(resolutionsRequest(order).header(HttpHeaders.AUTHORIZATION, tokens.bearer(7L, "BUYER", "SELLER")))
                .andExpect(status().isForbidden());                                       // 403
    }

    @Test
    void 해결_기록_조회의_페이지_크기가_범위를_벗어나면_422다() throws Exception {
        // when & then
        mockMvc.perform(resolutionsRequest(nextOrderId()).param("size", "101")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isUnprocessableEntity());                             // 422
    }

    @Test
    void 묶음_보기는_해결_요청_1번을_1줄로_최신순으로_준다() throws Exception {
        // given: 첫 해결은 실패 기록 3건(성공한 실행 3번에서 멈춤), 다시 잠긴 뒤 두 번째 해결은 1건
        List<Long> runs = runCompleted(4);
        long order = nextOrderId();
        long candidate = insertCandidate(order, LocalDateTime.now().minusDays(30));
        insertFailure(runs.get(0), candidate, order, PayoutFailureReason.AMOUNT_MISMATCH);
        insertFailure(runs.get(1), candidate, order, PayoutFailureReason.AMOUNT_MISMATCH);
        insertFailure(runs.get(2), candidate, order, PayoutFailureReason.AMOUNT_MISMATCH);
        PayoutOrderResolutionResult first = resolve(order);
        insertFailure(runs.get(3), candidate, order, PayoutFailureReason.VERIFY_MISMATCH);
        PayoutOrderResolutionResult second = resolve(order);

        // when & then: 해결 기록은 4줄이지만 요청은 2줄, 나중 요청이 먼저
        mockMvc.perform(resolutionRequestsRequest(order).header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].resolutionRequestId")
                        .value(contains(second.resolutionRequestId(), first.resolutionRequestId())))
                .andExpect(jsonPath("$.content[*].resolvedFailureCount").value(contains(1, 3)))
                .andExpect(jsonPath("$.content[1].orderId").value(order))
                .andExpect(jsonPath("$.content[1].method").value("REPROCESS"))
                .andExpect(jsonPath("$.content[1].adminId").value(ADMIN_ID))
                .andExpect(jsonPath("$.content[1].resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.content[1].memo").value(MEMO));
        assertThat(resolutionRepository.findAllByOrderId(order)).hasSize(4);
    }

    @Test
    void 묶음_보기도_페이지를_요청_단위로_나눈다() throws Exception {
        // given: 해결 요청 2번 (첫 요청은 줄 2개)
        List<Long> runs = runCompleted(3);
        long order = nextOrderId();
        long candidate = insertCandidate(order, LocalDateTime.now().minusDays(30));
        insertFailure(runs.get(0), candidate, order, PayoutFailureReason.VERIFY_MISMATCH);
        insertFailure(runs.get(1), candidate, order, PayoutFailureReason.VERIFY_MISMATCH);
        PayoutOrderResolutionResult first = resolve(order);
        insertFailure(runs.get(2), candidate, order, PayoutFailureReason.VERIFY_MISMATCH);
        resolve(order);

        // when & then: 크기 1이면 요청 수만큼 2쪽, 둘째 쪽은 첫 요청 (줄 수 3이 아니라 요청 수 2로 셈)
        mockMvc.perform(resolutionRequestsRequest(order).param("page", "1").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].resolutionRequestId").value(first.resolutionRequestId()))
                .andExpect(jsonPath("$.content[0].resolvedFailureCount").value(2));
    }

    @Test
    void 묶음_보기는_해결_기록이_없으면_빈_목록이고_관리자만_볼_수_있다() throws Exception {
        // given
        long order = givenLockedOrder();

        // when & then
        mockMvc.perform(resolutionRequestsRequest(order).header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
        mockMvc.perform(resolutionRequestsRequest(order))
                .andExpect(status().isUnauthorized());                                    // 401
        mockMvc.perform(resolutionRequestsRequest(order).header(HttpHeaders.AUTHORIZATION, tokens.bearer(7L, "BUYER", "SELLER")))
                .andExpect(status().isForbidden());                                       // 403
    }

    // ---------- 도우미 ----------

    private MockHttpServletRequestBuilder resolutionsRequest(long orderId) {
        return get("/api/v1/admin/payouts/orders/{orderId}/resolutions", orderId);
    }

    private MockHttpServletRequestBuilder resolutionRequestsRequest(long orderId) {
        return get("/api/v1/admin/payouts/orders/{orderId}/resolution-requests", orderId);
    }

    // 종류·주문상품·금액을 정해 정산후보를 넣음 (배송비는 orderItemId null). 지갑 결제 기록은 넣지 않음: 해결 기록 조회만 보는 테스트용
    private long insertCandidateOf(long orderId, PayoutEventType eventType, Long orderItemId, long amount) {
        LocalDateTime createdAt = LocalDateTime.now().minusDays(30);
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                eventType.name(), orderId, orderItemId, SELLER_ID, amount, createdAt, createdAt);
        for (PayoutCandidateItem candidate : candidateRepository.findAllByOrderId(orderId)) {
            if (candidate.getEventType() == eventType && Objects.equals(candidate.getOrderItemId(), orderItemId)) {
                return candidate.getId();
            }
        }
        throw new IllegalStateException("정산후보가 없습니다. orderId=" + orderId);
    }

    // 실제 실패 기록처럼 후보의 주문상품 ID를 복사하고 상세를 정해 넣음 (잠그는 사유 VERIFY_MISMATCH)
    private void insertFailureOf(Long snapshotId, long candidateId, long orderId, Long orderItemId, String detail) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO payout_failure (snapshot_id, candidate_id, order_id, order_item_id, seller_id, reason, detail, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshotId, candidateId, orderId, orderItemId, SELLER_ID, PayoutFailureReason.VERIFY_MISMATCH.name(),
                detail, now, now);
    }

    private PayoutOrderResolutionResult resolve(long orderId) {
        return resolutionUseCase.resolveOrder(new PayoutOrderResolveCommand(orderId, ADMIN_ID, MEMO));
    }

    private MockHttpServletRequestBuilder resolveRequest(long orderId, String body) {
        return post("/api/v1/admin/payouts/orders/{orderId}/resolution", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    // 다른 주문 하나를 대기(15일)가 지난 정상 후보로 둬, 다음 실행이 정산을 만들게 함
    // 조회 하한은 "정산을 만든 마지막 정기 실행" 기준이라, 정산이 없으면 하한이 없어 오래된 후보도 기간 안으로 들어옴 (#390)
    private void givenBaselineSettlement() {
        insertCandidate(nextOrderId(), LocalDateTime.now().minusDays(16));
    }

    // 결과 검증 불일치 실패 1건으로 잠긴 주문 (실행을 먼저 만들어 실제 Job이 그 후보를 정산하지 않게 함)
    private long givenLockedOrder() throws Exception {
        Long run = runCompleted();
        long order = nextOrderId();
        long candidate = insertCandidate(order, LocalDateTime.now().minusDays(30));
        insertFailure(run, candidate, order, PayoutFailureReason.VERIFY_MISMATCH);
        return order;
    }

    // 실제 정산 Job을 times번 돌려 COMPLETED 실행을 만들고 스냅샷 ID들을 돌려줌
    private List<Long> runCompleted(int times) throws Exception {
        List<Long> snapshotIds = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            snapshotIds.add(runCompleted());
        }
        return snapshotIds;
    }

    private Long runCompleted() throws Exception {
        JobExecution execution = payoutJobRunner.runScheduled();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        return snapshotIdOf(execution);
    }

    // 실제 Job을 돌린 뒤 실행 기록 상태를 FAILED로 바꿈 (#549 테스트와 같은 방법)
    private Long runFailed() throws Exception {
        JobExecution execution = payoutJobRunner.runScheduled();
        execution.setStatus(BatchStatus.FAILED);
        jobRepository.update(execution);
        return snapshotIdOf(execution);
    }

    private Long snapshotIdOf(JobExecution execution) {
        for (PayoutSnapshot snapshot : snapshotRepository.findAll()) {
            if (snapshot.getJobExecutionId().equals(execution.getId())) {
                return snapshot.getId();
            }
        }
        throw new IllegalStateException("실행에 해당하는 스냅샷이 없습니다. executionId=" + execution.getId());
    }

    // 정산후보 + 넉넉한 지갑 결제 기록: 금액 검사를 통과해 실제 Job이 정산할 수 있는 후보
    // 주의: 대기(15일)가 지난 후보를 실행보다 먼저 넣으면 그 실행이 바로 정산해 버림 → 실패 이력은 실행을 먼저 만들고 넣음
    private long insertCandidate(long orderId, LocalDateTime createdAt) {
        long candidateId = insertCandidateWithoutDeduction(orderId, createdAt);
        givenDeduction(orderId);
        return candidateId;
    }

    // 지갑 결제 기록 없는 후보: 실제 Job이 매번 "결제 기록 없음"(DEDUCTION_MISSING)으로 실패시킴 → 실제 흐름으로 멈춘 주문을 만듦
    // created_at을 원하는 시각으로 두려고 SQL로 넣음 (저장 시 Auditing이 시각을 덮어씀)
    private long insertCandidateWithoutDeduction(long orderId, LocalDateTime createdAt) {
        long orderItemId = ORDER_ITEM_ID_SEQ.incrementAndGet();
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                PayoutEventType.SALE_AMOUNT.name(), orderId, orderItemId, SELLER_ID, 1_000L, createdAt, createdAt);
        for (PayoutCandidateItem candidate : candidateRepository.findAllByOrderId(orderId)) {
            if (candidate.getOrderItemId().equals(orderItemId)) {
                return candidate.getId();
            }
        }
        throw new IllegalStateException("정산후보가 없습니다. orderId=" + orderId);
    }

    // 주문의 지갑 결제 기록 (금액은 넉넉히: 초과 청구 검사는 "후보 합계 ≤ 차감액")
    private void givenDeduction(long orderId) {
        if (!walletDeductionRepository.existsByOrderId(orderId)) {
            walletDeductionRepository.save(PayoutWalletDeduction.create(orderId, 1L, 1_000_000L, LocalDateTime.now()));
        }
    }

    private void insertFailure(Long snapshotId, long candidateId, long orderId, PayoutFailureReason reason) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO payout_failure (snapshot_id, candidate_id, order_id, order_item_id, seller_id, reason, detail, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?)",
                snapshotId, candidateId, orderId, SELLER_ID, reason.name(), "테스트", now, now);
    }

    // 스냅샷을 직접 만들고(가짜 실행 ID라 실패 횟수에 영향 없음) 들어간 후보 ID를 돌려줌
    private List<Long> snapshotCandidateIds() {
        return candidateIdsOf(createSnapshotNow());
    }

    // 조회 기간 [오늘-1일, 내일)로 스냅샷 생성: 지금 만든 후보는 기간 안, 30일 전 후보는 기간 밖
    private Long createSnapshotNow() {
        LocalDateTime now = LocalDateTime.now();
        return payoutService.createSnapshot(SNAPSHOT_EXECUTION_ID_SEQ.incrementAndGet(), now.minusDays(1), now.plusDays(1),
                PayoutRunType.SCHEDULED);
    }

    private List<Long> candidateIdsOf(Long snapshotId) {
        List<Long> ids = new ArrayList<>();
        for (PayoutSnapshotItem item : snapshotItemRepository.findBySnapshotId(snapshotId)) {
            ids.add(item.getCandidateId());
        }
        return ids;
    }

    private long nextOrderId() {
        return ORDER_ID_SEQ.incrementAndGet();
    }

    // 정산·draft·실패·해결·스냅샷은 전부, 후보·차감 기록은 이 클래스 번호대만 지움 (FK 때문에 자식 테이블 먼저)
    // 해결 기록은 실패 기록을 외래키로 가리켜 실패 기록보다 먼저 지움 (다른 통합 테스트의 정리는 해결 기록을 모름 → 여기서 반드시 비움)
    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM payout_outbox");
        jdbcTemplate.update("DELETE FROM payout_item");
        jdbcTemplate.update("DELETE FROM payout");
        jdbcTemplate.update("DELETE FROM payout_seller_draft_item");
        jdbcTemplate.update("DELETE FROM payout_seller_draft");
        jdbcTemplate.update("DELETE FROM payout_system_draft_item");
        jdbcTemplate.update("DELETE FROM payout_failure_resolution");
        jdbcTemplate.update("DELETE FROM payout_failure");
        jdbcTemplate.update("DELETE FROM payout_snapshot_item");
        jdbcTemplate.update("DELETE FROM payout_snapshot");
        jdbcTemplate.update("DELETE FROM payout_candidate_items WHERE order_id > ? AND order_id <= ?",
                ORDER_ID_START, ORDER_ID_END);
        jdbcTemplate.update("DELETE FROM payout_wallet_deduction WHERE order_id > ? AND order_id <= ?",
                ORDER_ID_START, ORDER_ID_END);
    }
}
