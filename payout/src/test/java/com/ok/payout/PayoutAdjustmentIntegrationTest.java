package com.ok.payout;

import com.ok.common.exception.RestApiException;
import com.ok.payout.adapter.in.batch.PayoutJobRunner;
import com.ok.payout.adapter.out.persistence.PayoutCandidateItemJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutFailureJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutOutboxJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutSnapshotJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutWalletDeductionJpaRepository;
import com.ok.payout.application.port.in.PayoutAdjustmentCreateCommand;
import com.ok.payout.application.port.in.PayoutAdjustmentCreateResult;
import com.ok.payout.application.port.in.PayoutFailureResolutionUseCase;
import com.ok.payout.application.port.in.PayoutOrderResolveCommand;
import com.ok.payout.application.port.in.PayoutUseCase;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 통합 테스트: 관리자 조정(#291)을 API와 실제 정산 Job으로 확인
//
// [확인하는 것] (괄호는 #291 결정 번호, 근거는 docs/decisions/payout/291-payout-admin-adjustment.md)
//   - 조정 생성: 번호 1, 2…, 같은 조정 재전송·동시 요청은 409(3), 배송비는 422(6), 원래 후보 없으면 422(7), 판매자는 원래 후보에서 가져옴(7)
//   - 조정은 원래 후보를 따라 정산됨(1): 원래가 대기 중이면 함께 기다리고, 이미 정산됐으면 다음 배치
//   - 판매자 합계가 0원 이하면 보류하고 다음 매출에서 상계(2), 보류된 원래 후보는 조회 하한 밖으로 밀려도 다시 들어감(2)
//     보류 후보는 최근 스냅샷(기준일 − 대기 일수 × 2 뒤)의 draft에서 찾음: 창 안이면 다시 들어가고 창 밖이면 안 찾음
//   - 초과 청구는 "실패 → 조정 → 해결" 순서로 넣은 조정으로만 풀림(4), 수수료는 대금·수수료를 함께 맞춰야 풀림(5)
//     확인한 조정의 세 조건(4): 실패 뒤에 넣음(미리 넣은 조정은 해결해도 안 셈), 금액 불일치 실패만(결제 기록 없음 실패는 안 셈),
//     이번 스냅샷에 들었거나 이미 정산됨(스냅샷을 만든 뒤 넣은 조정은 그 검사 합계에 안 셈)
//
// [준비]
// 후보·차감 기록은 리포지토리로 직접 저장하고, 생성 시각만 SQL로 과거로 옮김 (JPA 저장은 Auditing이 현재 시각으로 채움)
// 금액 예시는 수수료 3% 버림(PayoutPolicy.calculateSaleFee) 기준: 판매 10,000 = 대금 9,700 + 수수료 300
// 조회 하한이 정산이 있는 정기 실행에서 구해져, 정산·스냅샷 등을 테스트 전후로 전부 비움 (PayoutAmountCheckFlowIntegrationTest와 같은 방식)
class PayoutAdjustmentIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 번호대 (다른 정산 테스트는 3_000_000~, 4_000_000~ 등을 사용)
    private static final long ORDER_ID_START = 2_500_000L;
    private static final long ORDER_ID_END = 2_600_000L;
    private static final long ORDER_A = 2_500_001L;
    private static final long ORDER_B = 2_500_002L;
    private static final long ITEM_A = 2_500_101L;
    private static final long ITEM_B = 2_500_102L;
    // 주문 A의 두 번째 주문 항목 (같은 주문에서 한 상품만 대기 중인 경우)
    private static final long ITEM_A_SECOND = 2_500_103L;
    // 원래 후보가 없는 주문 항목 (생성 거절 확인용)
    private static final long ITEM_WITHOUT_ORIGINAL = 2_500_199L;

    private static final long SELLER = 2_501L;
    private static final long BUYER = 2_510L;
    private static final long ADMIN = 1L;
    private static final String REASON = "금액 오입력 정정";
    private static final String MEMO = "조정으로 금액을 바로잡음";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private TestAccessTokens tokens;

    @Autowired
    private PayoutUseCase payoutUseCase;

    @Autowired
    private PayoutFailureResolutionUseCase resolutionUseCase;

    @Autowired
    private PayoutJobRunner payoutJobRunner;

    @Autowired
    private PayoutCandidateItemJpaRepository candidateRepository;

    @Autowired
    private PayoutWalletDeductionJpaRepository deductionRepository;

    @Autowired
    private PayoutJpaRepository payoutRepository;

    @Autowired
    private PayoutOutboxJpaRepository outboxRepository;

    @Autowired
    private PayoutFailureJpaRepository failureRepository;

    @Autowired
    private PayoutSnapshotJpaRepository snapshotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Job 도중을 흉내 내려고 금액 검사 합계 조회를 직접 부름 (스냅샷 범위 테스트만 씀)
    @Autowired
    private PayoutQueryRepository payoutQueryRepository;

    @BeforeEach
    void setUp() {
        cleanUp();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ---------- 조정 생성 ----------

    // API: POST /api/v1/admin/payouts/adjustments
    // 확인: 201, 번호 1, 주문·판매자는 원래 후보의 값, 조정 정산후보가 실제로 저장됨
    @Test
    void 관리자가_조정을_넣으면_201과_번호_1의_조정이_원래_후보의_판매자로_저장된다() throws Exception {
        // given: 주문 A의 항목 A 판매 10,000 (대금 9,700)
        givenSale(ORDER_A, ITEM_A, SELLER, 10_000L);

        // when & then
        mockMvc.perform(adjustmentRequest("""
                        {"orderItemId": 2500101, "eventType": "SALE_AMOUNT", "amount": -2000, "reason": "금액 오입력 정정"}
                        """).header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN, "ADMIN")))
                .andExpect(status().isCreated())                                         // 201
                .andExpect(jsonPath("$.adjustmentId").value(1))
                .andExpect(jsonPath("$.orderId").value(ORDER_A))
                .andExpect(jsonPath("$.orderItemId").value(ITEM_A))
                .andExpect(jsonPath("$.sellerId").value(SELLER))
                .andExpect(jsonPath("$.eventType").value("SALE_AMOUNT"))
                .andExpect(jsonPath("$.amount").value(-2000))
                .andExpect(jsonPath("$.reason").value(REASON))
                .andExpect(jsonPath("$.adminId").value(ADMIN))
                .andExpect(jsonPath("$.candidateId").isNumber())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        // then: 원래 2건(대금·수수료) + 조정 1건
        List<PayoutCandidateItem> adjustments = adjustmentsOf(ITEM_A);
        assertThat(adjustments)
                .extracting(PayoutCandidateItem::getAdjustmentId, PayoutCandidateItem::getSellerId,
                        PayoutCandidateItem::getOrderId, PayoutCandidateItem::getAmount, PayoutCandidateItem::getAdminId)
                .containsExactly(tuple(1L, SELLER, ORDER_A, -2_000L, ADMIN));
    }

    @Test
    void 같은_항목에_사유가_다른_조정을_또_넣으면_번호가_2가_된다() throws Exception {
        // given
        givenSale(ORDER_A, ITEM_A, SELLER, 10_000L);
        adjust(ITEM_A, "SALE_AMOUNT", -2_000L, REASON);

        // when
        PayoutAdjustmentCreateResult sut = adjust(ITEM_A, "SALE_AMOUNT", -2_000L, "배송 지연 보상");

        // then
        assertThat(sut.adjustmentId()).isEqualTo(2L);
    }

    // API: POST /api/v1/admin/payouts/adjustments
    // 확인: 응답을 못 받은 화면이 같은 요청을 다시 보내면 409이고 조정이 하나만 남음 (같은 돈이 두 번 빠지지 않음)
    @Test
    void 같은_항목_금액_사유의_조정을_다시_보내면_409이고_조정은_하나만_남는다() throws Exception {
        // given: 같은 조정을 한 번 넣음
        givenSale(ORDER_A, ITEM_A, SELLER, 10_000L);
        adjust(ITEM_A, "SALE_AMOUNT", -2_000L, REASON);

        // when & then: 사유 앞뒤 공백만 다른 재전송도 같은 조정으로 봄 (요청 DTO가 공백을 지움)
        mockMvc.perform(adjustmentRequest("""
                        {"orderItemId": 2500101, "eventType": "SALE_AMOUNT", "amount": -2000, "reason": "  금액 오입력 정정  "}
                        """).header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN, "ADMIN")))
                .andExpect(status().isConflict())                                        // 409
                .andExpect(jsonPath("$.type").value(endsWith("/payout/duplicate-adjustment")));

        // then
        assertThat(adjustmentsOf(ITEM_A)).hasSize(1);
    }

    // 동시에 들어온 두 요청은 둘 다 "같은 조정 없음"을 보고 같은 번호(최대 + 1)를 받음 → UK가 두 번째를 막고 409
    @Test
    void 같은_조정을_동시에_두_번_보내면_하나만_저장되고_나머지는_같은_조정_오류다() throws Exception {
        // given
        givenSale(ORDER_A, ITEM_A, SELLER, 10_000L);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> request = () -> {
            start.await();
            try {
                return adjust(ITEM_A, "SALE_AMOUNT", -2_000L, REASON);
            } catch (RuntimeException e) {
                return e;
            }
        };

        // when
        List<Object> outcomes = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(request);
            Future<Object> second = executor.submit(request);
            start.countDown();
            outcomes.add(first.get());
            outcomes.add(second.get());
        } finally {
            executor.shutdown();
        }

        // then: 하나는 저장, 하나는 DUPLICATE_ADJUSTMENT (500이 아님)
        assertThat(outcomes).filteredOn(PayoutAdjustmentCreateResult.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(RestApiException.class::isInstance)
                .singleElement()
                .satisfies(e -> assertThat(((RestApiException) e).getErrorCode()).isEqualTo(PayoutErrorCode.DUPLICATE_ADJUSTMENT));
        assertThat(adjustmentsOf(ITEM_A)).hasSize(1);
    }

    // API: POST /api/v1/admin/payouts/adjustments
    // 확인: 배송비 조정은 422 (요청 DTO의 허용 값에서 뺌)
    @Test
    void 배송비_조정을_요청하면_422이다() throws Exception {
        // when & then
        mockMvc.perform(adjustmentRequest("""
                        {"orderItemId": 2500101, "eventType": "SALE_SHIPPING_FEE", "amount": -2000, "reason": "금액 오입력 정정"}
                        """).header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN, "ADMIN")))
                .andExpect(status().isUnprocessableEntity());                           // 422
    }

    // API: POST /api/v1/admin/payouts/adjustments
    // 확인: 원래 후보가 없는 주문 항목이면 422이고 조정이 생기지 않음 (따라갈 원래 후보·판매자가 없음)
    @Test
    void 원래_후보가_없는_주문_항목에_조정을_요청하면_422이고_조정이_생기지_않는다() throws Exception {
        // when & then
        mockMvc.perform(adjustmentRequest("""
                        {"orderItemId": 2500199, "eventType": "SALE_AMOUNT", "amount": -2000, "reason": "금액 오입력 정정"}
                        """).header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN, "ADMIN")))
                .andExpect(status().isUnprocessableEntity())                             // 422
                .andExpect(jsonPath("$.type").value(endsWith("/payout/adjustment-original-not-found")));

        // then
        assertThat(candidateRepository.findAllByOrderItemId(ITEM_WITHOUT_ORIGINAL)).isEmpty();
    }

    // ---------- 조정은 원래 후보를 따라 정산됨 (결정 1번) ----------

    // 원래 9,700(주문 A)이 아직 15일 대기 중일 때 −2,000 조정을 넣음. 같은 판매자의 주문 B 9,700은 1차에 바로 정산 대상
    // → 1차: 주문 B만 9,700 (조정만 먼저 정산되면 B에서 −2,000이 빠져 7,700이 됨)
    // → 주문 A가 15일을 채운 2차: 원래와 조정이 함께 들어가 7,700 지급
    // 같은 판매자의 다른 매출을 두는 이유: 매출이 없으면 조정이 혼자 새어 들어가도 합계 −2,000으로 보류되어(결정 2번) 1차 결과가 "정산 없음"으로 같아 결정 1번을 확인하지 못함
    @Test
    void 원래_후보가_대기_중일_때_넣은_조정은_함께_기다렸다가_같은_배치에서_상계된다() throws Exception {
        // given: 주문 A 판매 10,000(오늘 생성, 대기 중) + −2,000 조정, 주문 B 판매 10,000(16일 전, 정산 대상)
        givenSale(ORDER_A, ITEM_A, SELLER, 10_000L);
        adjust(ITEM_A, "SALE_AMOUNT", -2_000L, REASON);
        givenSale(ORDER_B, ITEM_B, SELLER, 10_000L);
        backdateOriginals(ORDER_B, 16);

        // when: 1차는 주문 A가 대기 중, 2차는 주문 A를 16일 전으로 옮긴 뒤
        JobExecution first = payoutJobRunner.runScheduled();
        backdateOriginals(ORDER_A, 16);
        JobExecution second = payoutJobRunner.runScheduled();

        // then: 1차는 주문 B만 (판매자 9,700, 시스템 몫 300), 조정은 아직 안 들어감
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(first)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 9_700L), tuple(null, 300L));
        // then: 2차에 주문 A와 조정이 함께: 판매자 9,700 − 2,000 = 7,700, 시스템 몫 300
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(second)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 7_700L), tuple(null, 300L));
    }

    // ---------- 0원 이하 판매자 보류 (결정 2번) ----------

    // 이미 9,700 지급된 판매자에게 −2,000 조정, 그 배치에 다른 매출 없음
    // → 보류: 정산·지갑 메시지·실패 기록 모두 없음. 새 매출(5,000 → 대금 4,850)이 들어온 배치에서 4,850 − 2,000 = 2,850 지급
    @Test
    void 원래가_지급된_뒤_넣은_음수_조정은_다음_배치에_바로_들어가고_합계가_음수면_보류했다가_새_매출에서_상계된다() throws Exception {
        // given: 1차에 원래 9,700 지급
        givenSale(ORDER_A, ITEM_A, SELLER, 10_000L);
        backdateOriginals(ORDER_A, 16);
        JobExecution first = payoutJobRunner.runScheduled();
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(first)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .contains(tuple(SELLER, 9_700L));
        long outboxCountAfterFirst = outboxRepository.count();
        adjust(ITEM_A, "SALE_AMOUNT", -2_000L, REASON);

        // when: 2차는 조정만(판매자 합계 −2,000), 3차는 새 매출이 들어온 뒤
        JobExecution second = payoutJobRunner.runScheduled();
        givenSale(ORDER_B, ITEM_B, SELLER, 5_000L);
        backdateOriginals(ORDER_B, 16);
        JobExecution third = payoutJobRunner.runScheduled();

        // then: 2차는 조정이 대기 없이 스냅샷에 들어갔지만 판매자 정산은 보류 (정산·지갑 메시지·실패 기록 없음)
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long secondSnapshotId = snapshotIdOf(second);
        assertThat(payoutRepository.findBySnapshotId(secondSnapshotId)).isEmpty();
        assertThat(failureRepository.findBySnapshotId(secondSnapshotId)).isEmpty();
        // then: 3차에 새 매출과 함께 상계 (판매자 2,850, 시스템 몫 150), 지갑 메시지는 3차 판매자 정산 1건만 늘어남
        assertThat(third.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(third)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 2_850L), tuple(null, 150L));
        assertThat(outboxRepository.count()).isEqualTo(outboxCountAfterFirst + 1);
    }

    // 판매 1,000(대금 970) + −2,000 조정이 같은 배치 → 판매자 −1,030 보류 (수수료 30은 시스템 몫으로 정산)
    // 원래 후보를 40일 전으로 옮겨 조회 하한(마지막 정기 실행일 − 18일) 밖으로 밀어도, 새 매출이 들어온 배치에 다시 들어가야 함
    @Test
    void 보류된_원래_후보는_조회_하한_밖으로_밀려도_다음_매출과_함께_정산된다() throws Exception {
        // given: 1차에 판매자 합계 970 − 2,000 = −1,030 → 보류
        givenSale(ORDER_A, ITEM_A, SELLER, 1_000L);
        adjust(ITEM_A, "SALE_AMOUNT", -2_000L, REASON);
        backdateOriginals(ORDER_A, 16);
        JobExecution first = payoutJobRunner.runScheduled();
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(first)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(null, 30L));

        // when: 원래 후보를 하한 밖(40일 전)으로 옮기고, 새 매출 5,000(대금 4,850)을 넣어 2차 실행
        backdateOriginals(ORDER_A, 40);
        givenSale(ORDER_B, ITEM_B, SELLER, 5_000L);
        backdateOriginals(ORDER_B, 16);
        JobExecution second = payoutJobRunner.runScheduled();

        // then: 970 − 2,000 + 4,850 = 3,820 (보류된 원래·조정이 다시 들어옴), 시스템 몫은 새 매출 수수료 150만
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(second)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 3_820L), tuple(null, 150L));
    }

    // 정산 상세의 수수료 합계는 회차가 아니라 "이 정산이 대금을 치른 주문상품"으로 셈 (#291 리뷰 지적 1)
    // 1차: 판매 1,000(대금 970 / 수수료 30) + 대금 조정 −2,000 → 판매자 보류, 수수료 30은 1차 시스템 몫
    // 2차: 판매 5,000(대금 4,850 / 수수료 150) → 판매자 3,820 (1차 대금·조정 포함)
    // → 2차 판매자 정산 상세의 수수료는 30 + 150 = 180 (2건). 같은 회차 시스템 몫 정산 ID는 2차 것(수수료 150만 들어 있음)
    @Test
    void 보류됐다가_다음_회차에_정산되면_상세_수수료에_보류_회차_수수료도_들어간다() throws Exception {
        // given: 1차 보류
        givenSale(ORDER_A, ITEM_A, SELLER, 1_000L);
        adjust(ITEM_A, "SALE_AMOUNT", -2_000L, REASON);
        backdateOriginals(ORDER_A, 16);
        JobExecution first = payoutJobRunner.runScheduled();
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(first)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(null, 30L));

        // given: 2차에 새 매출과 함께 정산
        givenSale(ORDER_B, ITEM_B, SELLER, 5_000L);
        backdateOriginals(ORDER_B, 16);
        JobExecution second = payoutJobRunner.runScheduled();
        Payout sellerPayout = payoutOf(snapshotIdOf(second), SELLER);
        Payout secondSystemPayout = payoutOf(snapshotIdOf(second), null);
        assertThat(sellerPayout.getAmount()).isEqualTo(3_820L);

        // when & then
        mockMvc.perform(get("/api/v1/admin/payouts/{payoutId}", sellerPayout.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerFeeAmount").value(180))
                .andExpect(jsonPath("$.sellerFeeItemCount").value(2))
                .andExpect(jsonPath("$.systemPayoutId").value(secondSystemPayout.getId().intValue()));
    }

    // 보류된 회차에 수수료 조정도 함께 들어가면, 원래 수수료와 수수료 조정이 모두 보류 회차 시스템 몫에 들어감
    // 1차: 판매 1,000(대금 970 / 수수료 30), 대금 조정 −2,000, 수수료 조정 −10 → 판매자 보류, 시스템 몫 30 − 10 = 20
    // 2차: 판매 5,000(대금 4,850 / 수수료 150) → 판매자 970 − 2,000 + 4,850 = 3,820
    // → 2차 판매자 정산 상세의 수수료는 30 − 10 + 150 = 170 (3건)
    @Test
    void 보류_회차에_수수료_조정도_들어가면_상세_수수료에_원래_수수료와_조정이_함께_들어간다() throws Exception {
        // given: 1차 보류
        givenSale(ORDER_A, ITEM_A, SELLER, 1_000L);
        adjust(ITEM_A, "SALE_AMOUNT", -2_000L, REASON);
        adjust(ITEM_A, "SALE_FEE", -10L, REASON);
        backdateOriginals(ORDER_A, 16);
        JobExecution first = payoutJobRunner.runScheduled();
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(first)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactly(tuple(null, 20L));

        // given: 2차에 새 매출과 함께 정산
        givenSale(ORDER_B, ITEM_B, SELLER, 5_000L);
        backdateOriginals(ORDER_B, 16);
        JobExecution second = payoutJobRunner.runScheduled();
        Payout sellerPayout = payoutOf(snapshotIdOf(second), SELLER);
        assertThat(sellerPayout.getAmount()).isEqualTo(3_820L);

        // when & then
        mockMvc.perform(get("/api/v1/admin/payouts/{payoutId}", sellerPayout.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerFeeAmount").value(170))
                .andExpect(jsonPath("$.sellerFeeItemCount").value(3));
    }

    // 원래 대금이 1차에 지급된 뒤 넣은 대금 조정은 다른 정산(3차)에 들어감
    // 수수료는 원래 대금이 든 정산에만 붙어야 같은 수수료가 두 정산 상세에 겹쳐 세어지지 않음
    // 1차: 판매 10,000(대금 9,700 / 수수료 300) 지급, 2차: 조정 −2,000만 → 보류, 3차: 판매 5,000(수수료 150)과 함께 2,850
    // → 1차 정산 상세 수수료 300(1건), 3차 정산 상세 수수료 150(1건, 주문상품 A의 조정은 들었지만 A의 수수료 300은 1차에 이미 붙음)
    @Test
    void 대금_조정만_다른_정산에_들어가면_그_주문상품의_수수료는_원래_대금이_든_정산에만_붙는다() throws Exception {
        // given
        givenSale(ORDER_A, ITEM_A, SELLER, 10_000L);
        backdateOriginals(ORDER_A, 16);
        JobExecution first = payoutJobRunner.runScheduled();
        adjust(ITEM_A, "SALE_AMOUNT", -2_000L, REASON);
        payoutJobRunner.runScheduled();
        givenSale(ORDER_B, ITEM_B, SELLER, 5_000L);
        backdateOriginals(ORDER_B, 16);
        JobExecution third = payoutJobRunner.runScheduled();
        Payout firstPayout = payoutOf(snapshotIdOf(first), SELLER);
        Payout thirdPayout = payoutOf(snapshotIdOf(third), SELLER);
        assertThat(thirdPayout.getAmount()).isEqualTo(2_850L);

        // when & then
        mockMvc.perform(get("/api/v1/admin/payouts/{payoutId}", firstPayout.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerFeeAmount").value(300))
                .andExpect(jsonPath("$.sellerFeeItemCount").value(1));
        mockMvc.perform(get("/api/v1/admin/payouts/{payoutId}", thirdPayout.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerFeeAmount").value(150))
                .andExpect(jsonPath("$.sellerFeeItemCount").value(1));
    }

    // 수수료 조정이 나중 회차에 정산되면, 그 주문상품의 원래 대금이 든 지난 정산 상세의 수수료가 바뀜 (#291 리뷰 지적 1, 받아들인 대가)
    // 1차: 판매 10,000(대금 9,700 / 수수료 300) 지급 → 1차 상세 수수료 300(1건)
    // 수수료를 100 더 뗀 것을 바로잡는 조정: 대금 +100 / 수수료 −100 (원래가 지급된 뒤라 실패 없이 넣은 조정 = 확인 안 한 조정)
    //   확인 안 한 조정은 금액 검사(초과 청구·수수료 짝)에 들어가지 않아 원래 9,700 / 300으로 검사해 통과함 (PayoutAmountValidator)
    // 2차: 조정만 들어가 판매자 100, 시스템 몫 −100
    // → 1차 상세 수수료 200(2건)으로 바뀜. 2차 판매자 정산은 원래 대금이 없어 수수료 0(0건, null 아님)
    @Test
    void 수수료_조정이_나중_회차에_정산되면_원래_대금이_든_지난_정산_상세의_수수료가_바뀐다() throws Exception {
        // given: 1차 지급, 그때 상세 수수료는 300
        givenSale(ORDER_A, ITEM_A, SELLER, 10_000L);
        backdateOriginals(ORDER_A, 16);
        JobExecution first = payoutJobRunner.runScheduled();
        Payout firstPayout = payoutOf(snapshotIdOf(first), SELLER);
        mockMvc.perform(get("/api/v1/admin/payouts/{payoutId}", firstPayout.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerFeeAmount").value(300))
                .andExpect(jsonPath("$.sellerFeeItemCount").value(1));

        // given: 수수료를 바로잡는 조정을 넣고 2차 실행
        adjust(ITEM_A, "SALE_AMOUNT", 100L, REASON);
        adjust(ITEM_A, "SALE_FEE", -100L, REASON);
        JobExecution second = payoutJobRunner.runScheduled();
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(failureRepository.findBySnapshotId(snapshotIdOf(second))).isEmpty();
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(second)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 100L), tuple(null, -100L));
        Payout secondPayout = payoutOf(snapshotIdOf(second), SELLER);

        // when & then: 1차 상세의 수수료가 300 − 100 = 200 (원래 + 조정 2건), 판매자가 봐도 같음
        mockMvc.perform(get("/api/v1/admin/payouts/{payoutId}", firstPayout.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerFeeAmount").value(200))
                .andExpect(jsonPath("$.sellerFeeItemCount").value(2));
        mockMvc.perform(get("/api/v1/payouts/me/{payoutId}", firstPayout.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER, "BUYER", "SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerFeeAmount").value(200))
                .andExpect(jsonPath("$.sellerFeeItemCount").value(2));
        // then: 2차 판매자 정산은 대금 조정만 있어 수수료가 붙지 않음 (같은 수수료를 두 상세에 겹쳐 세지 않음), 값은 null이 아니라 0
        mockMvc.perform(get("/api/v1/payouts/me/{payoutId}", secondPayout.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER, "BUYER", "SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerFeeAmount").value(0))
                .andExpect(jsonPath("$.sellerFeeItemCount").value(0));
    }

    // 보류 조회는 최근 스냅샷(기준일 − 대기 일수 × 2 뒤)의 draft에서만 찾음 (PayoutPolicy.HELD_LOOKBACK_DAYS)
    // 1차 스냅샷을 25일 전으로 옮겨도 창(30일) 안이라 다시 들어감: 1차 실행이 기준일이라 창 시작은 오늘 − 30일
    @Test
    void 보류된_후보는_창_안의_오래된_draft에서도_다시_찾아_정산된다() throws Exception {
        // given: 1차에 판매자 합계 970 − 2,000 = −1,030 → 보류, 그 스냅샷을 25일 전으로 옮김
        givenSale(ORDER_A, ITEM_A, SELLER, 1_000L);
        adjust(ITEM_A, "SALE_AMOUNT", -2_000L, REASON);
        backdateOriginals(ORDER_A, 40);
        JobExecution first = payoutJobRunner.runScheduled();
        backdateSnapshot(snapshotIdOf(first), 25);

        // when: 새 매출 5,000(대금 4,850)
        givenSale(ORDER_B, ITEM_B, SELLER, 5_000L);
        backdateOriginals(ORDER_B, 16);
        JobExecution sut = payoutJobRunner.runScheduled();

        // then: 970 − 2,000 + 4,850 = 3,820
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(sut)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 3_820L), tuple(null, 150L));
    }

    // 창 밖(35일 전) 스냅샷의 draft에만 있는 보류 후보는 찾지 않음 (창이 실제로 범위를 거르는지 확인)
    // 실제로는 배치마다 보류 후보가 다시 draft에 오르고, 기준일이 성공한 실행이라 이런 경우가 생기지 않음 (알고 감)
    @Test
    void 창_밖_draft에만_있는_보류_후보는_다시_찾지_않는다() throws Exception {
        // given: 1차 보류 후 그 스냅샷을 35일 전으로 옮김
        givenSale(ORDER_A, ITEM_A, SELLER, 1_000L);
        adjust(ITEM_A, "SALE_AMOUNT", -2_000L, REASON);
        backdateOriginals(ORDER_A, 40);
        JobExecution first = payoutJobRunner.runScheduled();
        backdateSnapshot(snapshotIdOf(first), 35);

        // when
        givenSale(ORDER_B, ITEM_B, SELLER, 5_000L);
        backdateOriginals(ORDER_B, 16);
        JobExecution sut = payoutJobRunner.runScheduled();

        // then: 원래 970이 빠져 새 매출 4,850만. 조정 −2,000도 원래를 따라 빠짐
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(sut)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 4_850L), tuple(null, 150L));
    }

    // ---------- 확인한 조정으로 금액 불일치 풀기 (결정 4·5번) ----------

    // 결제 30,000인데 판매 40,000(대금 38,800 / 수수료 1,200)으로 구매확정 → 초과 청구로 3번 실패해 멈춤
    // → 실패를 보고 대금 −9,700, 수수료 −300 조정 → "다시 처리"로 해결 → 다음 배치에 판매자 29,100, 시스템 900
    @Test
    void 초과_청구로_멈춘_주문은_실패_뒤_넣은_조정과_해결로_맞는_금액이_정산된다() throws Exception {
        // given: 3번 실패해 멈춘 주문
        givenOverchargedOrderStopped();

        // when: 실패 → 조정 → 해결 순서
        adjust(ITEM_A, "SALE_AMOUNT", -9_700L, REASON);
        adjust(ITEM_A, "SALE_FEE", -300L, REASON);
        resolve(ORDER_A);
        JobExecution sut = payoutJobRunner.runScheduled();

        // then
        assertThat(sut.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long snapshotId = snapshotIdOf(sut);
        assertThat(failureRepository.findBySnapshotId(snapshotId)).isEmpty();
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 29_100L), tuple(null, 900L));
    }

    // 실패가 나기 전에 넣은 조정은 관리자가 그 불일치를 보고 넣은 것이 아니라 검사에 넣지 않음 (#552가 막은 "조정이 불일치를 가림")
    @Test
    void 실패_전에_넣은_조정은_초과_청구를_가리지_않는다() throws Exception {
        // given: 결제 30,000, 판매 40,000. 첫 실행 전에 −10,000을 미리 넣음
        givenDeduction(ORDER_A, 30_000L);
        givenSale(ORDER_A, ITEM_A, SELLER, 40_000L);
        backdateOriginals(ORDER_A, 16);
        adjust(ITEM_A, "SALE_AMOUNT", -9_700L, REASON);
        adjust(ITEM_A, "SALE_FEE", -300L, REASON);

        // when
        JobExecution sut = payoutJobRunner.runScheduled();

        // then: 원래 합계 40,000 > 30,000이라 초과 청구, 돈이 나가지 않음
        Long snapshotId = snapshotIdOf(sut);
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getReason)
                .containsOnly(PayoutFailureReason.AMOUNT_MISMATCH);
        assertThat(payoutRepository.findBySnapshotId(snapshotId)).isEmpty();
    }

    // 해결한 뒤에 넣은 조정은 확인한 조정이 아님 → 다시 초과 청구로 실패 (다시 해결해도 확인이 되지 않아, 멈춘 뒤 맞는 조정 → 해결 → 해결 뒤 반대 조정으로 상쇄)
    @Test
    void 해결한_뒤에_넣은_조정은_검사에_들어가지_않아_다시_초과_청구로_실패한다() throws Exception {
        // given: 3번 실패해 멈춘 주문을 먼저 해결
        givenOverchargedOrderStopped();
        resolve(ORDER_A);

        // when: 해결 뒤 조정
        adjust(ITEM_A, "SALE_AMOUNT", -9_700L, REASON);
        adjust(ITEM_A, "SALE_FEE", -300L, REASON);
        JobExecution sut = payoutJobRunner.runScheduled();

        // then
        Long snapshotId = snapshotIdOf(sut);
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getReason)
                .containsOnly(PayoutFailureReason.AMOUNT_MISMATCH);
        assertThat(payoutRepository.findBySnapshotId(snapshotId)).isEmpty();
    }

    // 결정 4번 하한(실패 뒤에 넣은 조정만 셈)을 지킴: 실패 전에 미리 넣은 조정은 해결해도 확인한 조정이 아님
    // 하한이 없으면 실패 전에 다른 이유로 넣어 둔 조정이 해결만으로 초과 청구를 가려 돈이 더 나감
    // 대가(11/17 감수): 미리 넣은 관리자는 한 번 더 넣어야 하고, 두 번 빠진 만큼은 해결한 뒤 반대 금액 조정으로 상쇄함
    @Test
    void 실패_전에_미리_넣은_조정은_해결해도_검사에_들어가지_않아_다시_초과_청구로_실패한다() throws Exception {
        // given: 결제 30,000, 판매 40,000. 첫 실행 전에 −9,700 / −300을 미리 넣고 3번 실패해 멈춤
        givenDeduction(ORDER_A, 30_000L);
        givenSale(ORDER_A, ITEM_A, SELLER, 40_000L);
        backdateOriginals(ORDER_A, 16);
        adjust(ITEM_A, "SALE_AMOUNT", -9_700L, REASON);
        adjust(ITEM_A, "SALE_FEE", -300L, REASON);
        runUntilStopped(PayoutFailureReason.AMOUNT_MISMATCH);

        // when: 새 조정 없이 해결만
        resolve(ORDER_A);
        JobExecution sut = payoutJobRunner.runScheduled();

        // then: 미리 넣은 조정은 세지 않아 40,000 > 30,000, 돈이 나가지 않음
        Long snapshotId = snapshotIdOf(sut);
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getReason)
                .containsOnly(PayoutFailureReason.AMOUNT_MISMATCH);
        assertThat(payoutRepository.findBySnapshotId(snapshotId)).isEmpty();
    }

    // 미리 넣은 조정을 Spec 안내대로 바로잡는 순서: 멈춘 뒤 맞는 조정 → 해결 → 해결한 뒤에 미리 넣은 것의 반대 조정
    // 결제 30,000, 판매 40,000. 미리 −9,700 / −300(세지 않음) → 3번 실패해 멈춤 → 맞는 조정 −9,700 / −300 → 해결
    // → 다음 배치는 검사 30,000으로 통과하지만 두 쌍 모두 지급돼 판매자 19,400, 시스템 600
    // → 해결 뒤 반대 조정 +9,700 / +300은 검사에 섞이지 않고 지급에만 들어가 그다음 배치에 판매자 9,700, 시스템 300 → 합계 29,100 / 900
    // 반대 조정을 해결 전에 넣으면 그것도 확인한 조정이 되어 검사 합계가 40,000으로 돌아가 다시 실패함
    @Test
    void 미리_넣은_조정은_해결한_뒤_반대_조정을_넣어_상쇄하면_합계가_맞는_금액이_된다() throws Exception {
        // given: 미리 넣은 조정이 있는 채로 3번 실패해 멈춤 → 맞는 조정과 해결로 첫 지급
        givenDeduction(ORDER_A, 30_000L);
        givenSale(ORDER_A, ITEM_A, SELLER, 40_000L);
        backdateOriginals(ORDER_A, 16);
        adjust(ITEM_A, "SALE_AMOUNT", -9_700L, REASON);
        adjust(ITEM_A, "SALE_FEE", -300L, REASON);
        runUntilStopped(PayoutFailureReason.AMOUNT_MISMATCH);
        adjust(ITEM_A, "SALE_AMOUNT", -9_700L, "실패 확인 뒤 다시 넣은 조정");
        adjust(ITEM_A, "SALE_FEE", -300L, "실패 확인 뒤 다시 넣은 조정");
        resolve(ORDER_A);
        JobExecution first = payoutJobRunner.runScheduled();
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(first)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 19_400L), tuple(null, 600L));

        // when: 해결한 뒤 미리 넣은 조정의 반대 금액 조정
        adjust(ITEM_A, "SALE_AMOUNT", 9_700L, "미리 넣은 조정 상쇄");
        adjust(ITEM_A, "SALE_FEE", 300L, "미리 넣은 조정 상쇄");
        JobExecution sut = payoutJobRunner.runScheduled();

        // then: 모자랐던 9,700 / 300이 지급돼 두 배치 합계가 29,100 / 900
        assertThat(sut.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long snapshotId = snapshotIdOf(sut);
        assertThat(failureRepository.findBySnapshotId(snapshotId)).isEmpty();
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 9_700L), tuple(null, 300L));
    }

    // 결정 4번 사유 필터(금액 불일치 실패만)를 지킴: 결제 기록 없음(DEDUCTION_MISSING)으로 멈춘 동안 넣은 조정은 확인한 조정이 아님
    // 사유를 안 거르면 금액과 상관없는 실패의 해결이 그 사이에 넣은 조정을 확인한 것으로 만들어 초과 청구를 가림
    @Test
    void 결제_기록_없음으로_멈춘_동안_넣은_조정은_해결해도_초과_청구를_가리지_않는다() throws Exception {
        // given: 판매 40,000인데 결제 기록이 아직 없어 3번 실패해 멈춤
        candidateRepository.saveAll(List.of(
                PayoutCandidateItem.ofSaleAmount(ORDER_A, ITEM_A, SELLER, 38_800L),
                PayoutCandidateItem.ofSaleFee(ORDER_A, ITEM_A, SELLER, 1_200L)));
        backdateOriginals(ORDER_A, 16);
        runUntilStopped(PayoutFailureReason.DEDUCTION_MISSING);

        // when: 멈춘 동안 조정을 넣고, 결제 기록 30,000이 들어온 뒤 해결
        adjust(ITEM_A, "SALE_AMOUNT", -9_700L, REASON);
        adjust(ITEM_A, "SALE_FEE", -300L, REASON);
        givenDeduction(ORDER_A, 30_000L);
        resolve(ORDER_A);
        JobExecution sut = payoutJobRunner.runScheduled();

        // then: 조정을 세지 않아 40,000 > 30,000, 돈이 나가지 않음
        Long snapshotId = snapshotIdOf(sut);
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getReason)
                .containsOnly(PayoutFailureReason.AMOUNT_MISMATCH);
        assertThat(payoutRepository.findBySnapshotId(snapshotId)).isEmpty();
    }

    // 결정 4번 스냅샷 범위(이번 스냅샷에 들었거나 이미 정산된 조정만)를 지킴
    // Job 도중(스냅샷을 만든 뒤, 금액 검사 전) 조정·해결이 들어온 경우를 흉내 냄: 이미 만든 스냅샷으로 금액 검사 합계를 다시 구함
    //   이 조정은 그 스냅샷 목록에 없어 이번 지급에서는 빠지므로, 검사 합계에도 들어가면 안 됨
    //   들어가면 합계 30,000으로 통과하는데 지급은 원래 40,000 그대로라 10,000이 더 나감
    @Test
    void 스냅샷을_만든_뒤_넣은_조정은_그_스냅샷의_금액_검사_합계에_들어가지_않는다() throws Exception {
        // given: 3번 실패해 멈춘 주문, 마지막 실행의 스냅샷
        givenDeduction(ORDER_A, 30_000L);
        givenSale(ORDER_A, ITEM_A, SELLER, 40_000L);
        backdateOriginals(ORDER_A, 16);
        Long snapshotId = runUntilStopped(PayoutFailureReason.AMOUNT_MISMATCH);
        adjust(ITEM_A, "SALE_AMOUNT", -9_700L, REASON);
        adjust(ITEM_A, "SALE_FEE", -300L, REASON);
        resolve(ORDER_A);

        // when
        Map<Long, Long> sut = payoutQueryRepository.sumCandidateAmountByOrderInSnapshot(snapshotId);

        // then: 확인한 조정이지만 그 스냅샷 밖이라 원래 40,000만
        assertThat(sut).containsEntry(ORDER_A, 40_000L);
    }

    // 같은 주문의 다른 상품이 아직 15일 대기 중일 때: 초과 청구 합계에는 대기 중 원래 후보도 들어가므로(#195), 그 상품의 확인한 조정도 함께 셈
    // 결제 30,000, 상품 A 10,000(대상) + 상품 A2 30,000(대기 중, 10,000 초과) → 40,000으로 3번 실패해 멈춤
    // → A2에 −9,700 / −300 조정 → 해결 → 다음 배치는 30,000으로 통과해 A만 9,700, A2가 대기를 마친 배치에 A2와 조정이 함께 19,400
    // 조정을 세지 않으면 A2가 대기를 마칠 때까지 계속 실패해 A의 9,700까지 묶임
    @Test
    void 같은_주문의_다른_상품이_대기_중이어도_그_상품의_확인한_조정을_검사에_넣어_대상_상품은_바로_정산된다() throws Exception {
        // given: 상품 A는 16일 전, 상품 A2는 오늘 구매확정(대기 중). 3번 실패해 멈춤
        givenDeduction(ORDER_A, 30_000L);
        givenSale(ORDER_A, ITEM_A, SELLER, 10_000L);
        backdateOriginals(ORDER_A, 16);
        givenSale(ORDER_A, ITEM_A_SECOND, SELLER, 30_000L);
        runUntilStopped(PayoutFailureReason.AMOUNT_MISMATCH);

        // when: 대기 중인 상품 A2에 실패 → 조정 → 해결 순서로 맞춘 뒤 실행, 그다음 A2를 16일 전으로 옮겨 다시 실행
        adjust(ITEM_A_SECOND, "SALE_AMOUNT", -9_700L, REASON);
        adjust(ITEM_A_SECOND, "SALE_FEE", -300L, REASON);
        resolve(ORDER_A);
        JobExecution sut = payoutJobRunner.runScheduled();
        jdbcTemplate.update("UPDATE payout_candidate_items SET created_at = ?, updated_at = ? WHERE order_item_id = ? AND adjustment_id = 0",
                LocalDateTime.now().minusDays(16), LocalDateTime.now().minusDays(16), ITEM_A_SECOND);
        JobExecution later = payoutJobRunner.runScheduled();

        // then: 조정을 넣은 합계 30,000이라 통과, 상품 A만 판매자 9,700 / 시스템 300
        Long snapshotId = snapshotIdOf(sut);
        assertThat(failureRepository.findBySnapshotId(snapshotId)).isEmpty();
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 9_700L), tuple(null, 300L));
        // then: A2가 대기를 마친 배치에 원래와 조정이 함께: 판매자 29,100 − 9,700 = 19,400, 시스템 900 − 300 = 600
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(later)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 19_400L), tuple(null, 600L));
    }

    // 원래 후보 없는 조정(생성 API가 422로 막아 정상이면 없음, 그 전 데이터·SQL로 넣은 것)은 정산 대상에서 뺌
    // 같은 주문에 다른 상품의 원래 후보가 있으면 합계가 있어 "판정 불가"로 안 걸리고, 확인 안 한 조정이라 검사 없이 지급되던 구멍을 막음
    @Test
    void 원래_후보_없는_조정은_같은_주문에_다른_상품이_있어도_정산하지_않는다() throws Exception {
        // given: 주문 A 상품 A 판매 10,000(정산 대상) + 원래 후보가 없는 주문 항목의 +5,000 조정 (리포지토리로 직접 저장)
        givenSale(ORDER_A, ITEM_A, SELLER, 10_000L);
        backdateOriginals(ORDER_A, 16);
        candidateRepository.save(PayoutCandidateItem.manualAdjustment(PayoutEventType.SALE_AMOUNT, ORDER_A, ITEM_WITHOUT_ORIGINAL,
                SELLER, 5_000L, 1L, ADMIN, REASON));

        // when
        JobExecution sut = payoutJobRunner.runScheduled();

        // then: 상품 A만 판매자 9,700 / 시스템 300 (조정 +5,000은 나가지 않음)
        assertThat(payoutRepository.findBySnapshotId(snapshotIdOf(sut)))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 9_700L), tuple(null, 300L));
    }

    // 대금만 −10,000 줄이면 합계는 30,000으로 맞지만 수수료 짝이 28,800 / 1,200 (판매 30,000의 수수료는 900) → 수수료 불일치
    // 그대로 통과시키면 판매자는 300원 덜 받고 시스템이 300원을 더 가짐
    @Test
    void 대금만_줄인_조정은_수수료_불일치로_다시_막힌다() throws Exception {
        // given: 3번 실패해 멈춘 주문
        givenOverchargedOrderStopped();

        // when
        adjust(ITEM_A, "SALE_AMOUNT", -10_000L, REASON);
        resolve(ORDER_A);
        JobExecution sut = payoutJobRunner.runScheduled();

        // then
        Long snapshotId = snapshotIdOf(sut);
        assertThat(failureRepository.findBySnapshotId(snapshotId))
                .extracting(PayoutFailure::getReason)
                .contains(PayoutFailureReason.FEE_MISMATCH)
                .doesNotContain(PayoutFailureReason.AMOUNT_MISMATCH);
        assertThat(payoutRepository.findBySnapshotId(snapshotId)).isEmpty();
    }

    // 원래 수수료가 틀린 주문 항목(대금 9,000 / 수수료 1,000, 판매 10,000의 수수료는 300)은 수수료 불일치로 잠김
    // → 대금 +700 / 수수료 −700 조정 → 해결 → 9,700 / 300이라 통과, 판매자 9,700
    @Test
    void 수수료_불일치로_잠긴_주문은_대금과_수수료를_함께_맞춘_조정과_해결로_정산된다() throws Exception {
        // given: 수수료가 틀리게 들어온 주문 → 1차에 잠김
        givenDeduction(ORDER_A, 10_000L);
        candidateRepository.saveAll(List.of(
                PayoutCandidateItem.ofSaleAmount(ORDER_A, ITEM_A, SELLER, 9_000L),
                PayoutCandidateItem.ofSaleFee(ORDER_A, ITEM_A, SELLER, 1_000L)));
        backdateOriginals(ORDER_A, 16);
        JobExecution locked = payoutJobRunner.runScheduled();
        assertThat(failureRepository.findBySnapshotId(snapshotIdOf(locked)))
                .extracting(PayoutFailure::getReason)
                .contains(PayoutFailureReason.FEE_MISMATCH);

        // when
        adjust(ITEM_A, "SALE_AMOUNT", 700L, REASON);
        adjust(ITEM_A, "SALE_FEE", -700L, REASON);
        resolve(ORDER_A);
        JobExecution sut = payoutJobRunner.runScheduled();

        // then
        Long snapshotId = snapshotIdOf(sut);
        assertThat(failureRepository.findBySnapshotId(snapshotId)).isEmpty();
        assertThat(payoutRepository.findBySnapshotId(snapshotId))
                .extracting(Payout::getPayeeId, Payout::getAmount)
                .containsExactlyInAnyOrder(tuple(SELLER, 9_700L), tuple(null, 300L));
    }

    // ---------- 도우미 ----------

    // 결제 30,000, 판매 40,000(대금 38,800 / 수수료 1,200)인 주문을 3번 실행해 초과 청구로 멈춤 (#549)
    private void givenOverchargedOrderStopped() throws Exception {
        givenDeduction(ORDER_A, 30_000L);
        givenSale(ORDER_A, ITEM_A, SELLER, 40_000L);
        backdateOriginals(ORDER_A, 16);
        for (int run = 0; run < PayoutPolicy.MAX_FAILED_RUNS; run++) {
            JobExecution execution = payoutJobRunner.runScheduled();
            assertThat(failureRepository.findBySnapshotId(snapshotIdOf(execution)))
                    .extracting(PayoutFailure::getReason)
                    .containsOnly(PayoutFailureReason.AMOUNT_MISMATCH);
        }
    }

    // 이미 준비한 주문을 3번 실행해 멈춤. 매번 그 사유로만 실패했는지 확인하고, 마지막 실행의 스냅샷 ID를 돌려줌
    private Long runUntilStopped(PayoutFailureReason reason) throws Exception {
        Long lastSnapshotId = null;
        for (int run = 0; run < PayoutPolicy.MAX_FAILED_RUNS; run++) {
            JobExecution execution = payoutJobRunner.runScheduled();
            lastSnapshotId = snapshotIdOf(execution);
            assertThat(failureRepository.findBySnapshotId(lastSnapshotId))
                    .extracting(PayoutFailure::getReason)
                    .containsOnly(reason);
        }
        return lastSnapshotId;
    }

    // 판매 금액으로 구매확정 후보(대금·수수료)를 만들고, 차감 기록이 없으면 판매 금액만큼 넣음
    private void givenSale(long orderId, long orderItemId, long sellerId, long saleAmount) {
        long fee = PayoutPolicy.calculateSaleFee(saleAmount);
        candidateRepository.saveAll(List.of(
                PayoutCandidateItem.ofSaleAmount(orderId, orderItemId, sellerId, saleAmount - fee),
                PayoutCandidateItem.ofSaleFee(orderId, orderItemId, sellerId, fee)));
        if (!deductionRepository.existsByOrderId(orderId)) {
            givenDeduction(orderId, saleAmount);
        }
    }

    private void givenDeduction(long orderId, long amount) {
        deductionRepository.save(PayoutWalletDeduction.create(orderId, BUYER, amount, LocalDateTime.now()));
    }

    // 그 주문의 원래 후보(조정 아님)만 며칠 전으로 옮김: 조정은 오늘 만든 그대로 둬서 "원래를 따라가는지" 확인
    private void backdateOriginals(long orderId, int days) {
        LocalDateTime createdAt = LocalDateTime.now().minusDays(days);
        jdbcTemplate.update("UPDATE payout_candidate_items SET created_at = ?, updated_at = ? WHERE order_id = ? AND adjustment_id = 0",
                createdAt, createdAt, orderId);
    }

    // 스냅샷 생성 시각만 며칠 전으로 옮김: 보류 조회 창이 스냅샷 생성 시각으로 거르므로 (스냅샷 표는 @Immutable이라 테스트 준비용 SQL)
    private void backdateSnapshot(Long snapshotId, int days) {
        LocalDateTime createdAt = LocalDateTime.now().minusDays(days);
        jdbcTemplate.update("UPDATE payout_snapshot SET created_at = ?, updated_at = ? WHERE id = ?", createdAt, createdAt, snapshotId);
    }

    private PayoutAdjustmentCreateResult adjust(long orderItemId, String eventType, long amount, String reason) {
        return payoutUseCase.createAdjustment(ADMIN, new PayoutAdjustmentCreateCommand(orderItemId, eventType, amount, reason));
    }

    private void resolve(long orderId) {
        resolutionUseCase.resolveOrder(new PayoutOrderResolveCommand(orderId, ADMIN, MEMO));
    }

    private List<PayoutCandidateItem> adjustmentsOf(long orderItemId) {
        List<PayoutCandidateItem> adjustments = new ArrayList<>();
        for (PayoutCandidateItem candidate : candidateRepository.findAllByOrderItemId(orderItemId)) {
            if (candidate.getAdjustmentId() > PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID) {
                adjustments.add(candidate);
            }
        }
        return adjustments;
    }

    private MockHttpServletRequestBuilder adjustmentRequest(String body) {
        return post("/api/v1/admin/payouts/adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    // 한 스냅샷에서 수취인으로 정산 1건을 찾음 (payeeId null = 시스템 몫)
    private Payout payoutOf(Long snapshotId, Long payeeId) {
        for (Payout payout : payoutRepository.findBySnapshotId(snapshotId)) {
            if (Objects.equals(payout.getPayeeId(), payeeId)) {
                return payout;
            }
        }
        throw new IllegalStateException("정산이 없습니다. snapshotId=" + snapshotId + ", payeeId=" + payeeId);
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

    // 정산·draft·실패·해결·스냅샷·막힌 주문 표는 전부, 후보·차감 기록은 이 클래스 번호대만 지움 (FK 때문에 자식 테이블 먼저)
    // (리포지토리에는 삭제 기능이 없으므로 테스트 정리용으로만 SQL 직접 실행)
    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM payout_deposit_check");
        jdbcTemplate.update("DELETE FROM payout_outbox");
        jdbcTemplate.update("DELETE FROM payout_item");
        jdbcTemplate.update("DELETE FROM payout");
        jdbcTemplate.update("DELETE FROM payout_seller_draft_item");
        jdbcTemplate.update("DELETE FROM payout_seller_draft");
        jdbcTemplate.update("DELETE FROM payout_system_draft_item");
        jdbcTemplate.update("DELETE FROM payout_blocked_order_cause");
        jdbcTemplate.update("DELETE FROM payout_blocked_order");
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
