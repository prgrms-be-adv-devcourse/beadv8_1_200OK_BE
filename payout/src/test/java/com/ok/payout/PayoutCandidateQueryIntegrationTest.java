package com.ok.payout;

import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.payout.repository.PayoutRepository;
import com.ok.payout.repository.PayoutSnapshotRepository;
import com.ok.payout.repository.PayoutWalletDeductionRepository;
import com.ok.payout.service.PayoutJobRunner;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 통합 테스트: 정산후보 조회 API(판매자 /payouts/me/candidates, 관리자 /admin/payouts/candidates)를 실제 HTTP 요청과 MySQL 컨테이너로 확인 (#236)
// - 정산후보는 created_at을 과거로 넣어야 하므로 SQL로 직접 넣는다 (저장 시 Auditing이 현재 시각으로 덮어쓰기 때문)
// - "정산된 후보"는 실제 정산 배치를 돌려 만든다 (운영과 같은 구조로 정산 ID·미정산 판단을 확인하기 위해)
// - 결과 확인은 API 응답으로 하고, 정산 ID 비교에 쓸 값만 JPA 리포지토리로 꺼낸다. JDBC는 데이터 준비·정리에만 쓴다
// - 읽는 방법(mockMvc·jsonPath)은 PayoutQueryIntegrationTest 위의 설명과 같음
//
// [준비하는 데이터]
//   20일 전 (배치를 돌려 정산됨)
//     A 판매자 7 판매 대금 9,700   → 판매자 7 정산
//     B 판매자 7 수수료 300        → 시스템 몫 정산 (관리자 목록에만 나오고 판매자 목록에서는 빠짐)
//     C 판매자 7 조정 -2,000       → 판매자 7 정산 (조정 번호 4, 관리자 1, 사유 "금액 오입력 정정")
//     D 판매자 9 판매 대금 19,400  → 판매자 9 정산
//   1일 전 (배치를 돌린 뒤에 넣어 정산 전으로 남음)
//     E 판매자 7 판매 대금 5,000
class PayoutCandidateQueryIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 주문항목 ID 번호대 (다른 정산 테스트: 5_000_001, 6_000_000, 8_000_000, 9_000_000·9_500_000대)
    // 주문 ID에도 같은 번호를 쓰므로 정리는 order_id 이 범위로 함
    private static final long ORDER_ITEM_ID_START = 4_000_000L;
    private static final long ORDER_ITEM_ID_END = 5_000_000L;
    private static final AtomicLong ORDER_ITEM_ID_SEQ = new AtomicLong(ORDER_ITEM_ID_START);

    private static final long ADMIN_ID = 1L;
    private static final long SELLER_7 = 7L;
    private static final long SELLER_9 = 9L;
    private static final long BUYER_3 = 3L;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    // 요청에 붙일 로그인 토큰을 만듦 (실제 토큰과 같은 형식이라 보안 필터가 똑같이 검증함)
    @Autowired
    private TestAccessTokens tokens;

    @Autowired
    private PayoutJobRunner payoutJobRunner;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private PayoutSnapshotRepository snapshotRepository;

    @Autowired
    private PayoutWalletDeductionRepository payoutWalletDeductionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 후보 생성 시각 (초 단위로 잘라 넣어야 API 응답과 정확히 비교할 수 있음)
    private LocalDateTime oldCreatedAt;      // 20일 전: 배치로 정산됨
    private LocalDateTime recentCreatedAt;   // 1일 전: 정산 전

    // 조회 기간: 위 두 시각을 모두 포함 (30일 전 ~ 내일)
    private LocalDateTime listFrom;
    private LocalDateTime listTo;

    // 배치가 만든 정산 ID (DB가 매기므로 배치 후 조회해서 채움)
    private Long seller7PayoutId;
    private Long systemPayoutId;

    @BeforeEach
    void setUp() throws Exception {
        // 보안 필터를 적용해야 토큰 검증과 401·403 처리가 실제와 같게 동작한다 (DEVELOPMENT.md "테스트에서 토큰 보내기")
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        oldCreatedAt = now.minusDays(20);
        recentCreatedAt = now.minusDays(1);
        listFrom = now.minusDays(30);
        listTo = now.plusDays(1);

        long orderItemA = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemA, SELLER_7, 9_700L, oldCreatedAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderItemA, SELLER_7, 300L, oldCreatedAt);
        insertAdjustment(PayoutEventType.SALE_AMOUNT, orderItemA, SELLER_7, -2_000L, 4L, ADMIN_ID, "금액 오입력 정정", oldCreatedAt);
        insertCandidate(PayoutEventType.SALE_AMOUNT, nextOrderItemId(), SELLER_9, 19_400L, oldCreatedAt);

        JobExecution execution = payoutJobRunner.run();
        // 준비 단계 확인: 배치가 실패하면 아래 테스트가 모두 엉뚱한 이유로 실패하므로 여기서 바로 알림
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        rememberPayoutIds();

        // 배치를 돌린 뒤에 넣어서 정산 전으로 남김
        insertCandidate(PayoutEventType.SALE_AMOUNT, nextOrderItemId(), SELLER_7, 5_000L, recentCreatedAt);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ---------- 판매자 ----------

    // API: GET /api/v1/payouts/me/candidates (판매자 본인 정산후보 조회)
    // 요청: 판매자 7, 기간 30일 전 ~ 내일
    // 확인: 본인 후보 3건(A·C·E)만 나오고(수수료 B는 빠짐), 최신순이라 1일 전 E가 맨 앞이며 정산 전(settled=false, payoutId=null)인지
    @Test
    void 판매자는_본인_후보만_최신순으로_정산_대기와_완료가_함께_나온다() throws Exception {
        // when & then
        mockMvc.perform(sellerRequest(SELLER_7, "BUYER", "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].sellerId").value(contains(7, 7, 7)))      // 판매자 9의 D는 없음
                .andExpect(jsonPath("$.content[0].amount").value(5_000))                    // 가장 최근(1일 전) E
                .andExpect(jsonPath("$.content[0].settled").value(false))
                .andExpect(jsonPath("$.content[0].payoutId").value(nullValue()))
                .andExpect(jsonPath("$.content[0].candidateCreatedAt").value(json(recentCreatedAt)));
    }

    // API: GET /api/v1/payouts/me/candidates (판매자 본인 정산후보 조회)
    // 요청: 판매자 7
    // 확인: 정산된 판매 대금 후보 A는 settled=true이고 payoutId가 판매자 7 정산 ID인지
    @Test
    void 판매자의_정산된_판매_대금_후보는_본인_정산_ID를_받는다() throws Exception {
        // when & then: 조정이 아닌 판매 대금 중 정산된 칸 = A
        mockMvc.perform(sellerRequest(SELLER_7, "BUYER", "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.eventType == 'SALE_AMOUNT' && @.adjustment == null && @.settled == true)].payoutId")
                        .value(contains(seller7PayoutId.intValue())));
    }

    // API: GET /api/v1/payouts/me/candidates (판매자 본인 정산후보 조회)
    // 요청: 판매자 7
    // 확인: 수수료 후보 B가 나오지 않는지 (수수료는 시스템 몫 정산에 들어가 판매자에게 줄 단위로 보여 주지 않음, 합계는 정산 상세에서 봄)
    @Test
    void 판매자_목록에는_수수료_후보가_나오지_않는다() throws Exception {
        // when & then
        mockMvc.perform(sellerRequest(SELLER_7, "BUYER", "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.eventType == 'SALE_FEE')]").isEmpty());
    }

    // API: GET /api/v1/payouts/me/candidates (판매자 본인 정산후보 조회)
    // 요청: 판매자 7
    // 확인: 조정 후보 C에 사유만 있고 조정 번호·관리자 ID 칸은 아예 없는지 (#198과 같은 기준)
    @Test
    void 판매자가_조정_후보를_보면_사유만_있다() throws Exception {
        // when & then
        mockMvc.perform(sellerRequest(SELLER_7, "BUYER", "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].amount").value(contains(-2_000)))
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].adjustment.reason").value(contains("금액 오입력 정정")))
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].adjustment.adjustmentId").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].adjustment.adminId").doesNotExist());
    }

    // API: GET /api/v1/payouts/me/candidates (판매자 본인 정산후보 조회)
    // 요청: 판매자 7, unsettledOnly=true
    // 확인: 정산 전인 E 1건만 나오는지
    @Test
    void 판매자가_unsettledOnly를_켜면_정산_대기만_나온다() throws Exception {
        // when & then
        mockMvc.perform(sellerRequest(SELLER_7, "BUYER", "SELLER").param("unsettledOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(5_000))
                .andExpect(jsonPath("$.content[0].settled").value(false));
    }

    // API: GET /api/v1/payouts/me/candidates (판매자 본인 정산후보 조회)
    // 요청: 판매자 7, sellerId=9를 붙여 보냄 (판매자용 요청에는 없는 파라미터)
    // 확인: 무시되고 본인(판매자 7) 후보만 나오는지 (누구의 후보인지는 토큰으로만 정함)
    @Test
    void 판매자가_sellerId에_다른_판매자를_넣어도_본인_후보만_나온다() throws Exception {
        // when & then
        mockMvc.perform(sellerRequest(SELLER_7, "BUYER", "SELLER").param("sellerId", String.valueOf(SELLER_9)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].sellerId").value(contains(7, 7, 7)));
    }

    // ---------- 관리자 ----------

    // API: GET /api/v1/admin/payouts/candidates (관리자 정산후보 조회)
    // 요청: 관리자, sellerId 없음
    // 확인: 판매자 7·9의 후보 5건이 모두 나오는지
    @Test
    void 관리자는_sellerId_없이_전체_판매자의_후보를_본다() throws Exception {
        // when & then
        mockMvc.perform(adminRequest(ADMIN_ID, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.content[*].sellerId").value(containsInAnyOrder(7, 7, 7, 7, 9)));
    }

    // API: GET /api/v1/admin/payouts/candidates (관리자 정산후보 조회)
    // 요청: 관리자, sellerId=9
    // 확인: 판매자 9의 후보 D 1건만 나오는지
    @Test
    void 관리자가_sellerId로_거르면_그_판매자_후보만_나온다() throws Exception {
        // when & then
        mockMvc.perform(adminRequest(ADMIN_ID, "ADMIN").param("sellerId", String.valueOf(SELLER_9)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sellerId").value((int) SELLER_9))
                .andExpect(jsonPath("$.content[0].amount").value(19_400));
    }

    // API: GET /api/v1/admin/payouts/candidates (관리자 정산후보 조회)
    // 요청: 관리자, sellerId=7
    // 확인: 수수료 후보 B의 payoutId가 시스템 몫 정산 ID인지 (판매자와 달리 관리자에게는 숨기지 않음)
    @Test
    void 관리자는_수수료_후보의_시스템_몫_정산_ID를_받는다() throws Exception {
        // when & then
        mockMvc.perform(adminRequest(ADMIN_ID, "ADMIN").param("sellerId", String.valueOf(SELLER_7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.eventType == 'SALE_FEE')].payoutId")
                        .value(contains(systemPayoutId.intValue())));
    }

    // API: GET /api/v1/admin/payouts/candidates (관리자 정산후보 조회)
    // 요청: 관리자, sellerId=7
    // 확인: 조정 후보 C의 조정 번호·관리자 ID·사유가 모두 나오고, 판매자 7 정산에 들어갔는지
    @Test
    void 관리자는_조정_후보의_조정_번호와_관리자_ID를_받는다() throws Exception {
        // when & then
        mockMvc.perform(adminRequest(ADMIN_ID, "ADMIN").param("sellerId", String.valueOf(SELLER_7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].adjustment.adjustmentId").value(contains(4)))
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].adjustment.adminId").value(contains((int) ADMIN_ID)))
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].adjustment.reason").value(contains("금액 오입력 정정")))
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].payoutId").value(contains(seller7PayoutId.intValue())));
    }

    // API: GET /api/v1/admin/payouts/candidates (관리자 정산후보 조회)
    // 요청: 관리자, unsettledOnly=true
    // 확인: 전체 판매자 중 정산 전인 E 1건만 나오는지
    @Test
    void 관리자가_unsettledOnly를_켜면_정산_전인_후보만_나온다() throws Exception {
        // when & then
        mockMvc.perform(adminRequest(ADMIN_ID, "ADMIN").param("unsettledOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(5_000))
                .andExpect(jsonPath("$.content[0].payoutId").value(nullValue()));
    }

    // API: GET /api/v1/admin/payouts/candidates (관리자 정산후보 조회)
    // 요청: 관리자, 기간 from = 20일 전 시각(A~D 생성 시각), to = 1일 전 시각(E 생성 시각)
    // 확인: from과 같은 시각의 A~D는 포함, to와 같은 시각의 E는 제외되는지
    @Test
    void 기간은_from과_같은_시각을_포함하고_to와_같은_시각은_제외한다() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/admin/payouts/candidates")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN"))
                        .param("from", oldCreatedAt.toString())
                        .param("to", recentCreatedAt.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[?(@.amount == 5000)]").isEmpty());   // E(5,000원)는 없음
    }

    // API: GET /api/v1/admin/payouts/candidates (관리자 정산후보 조회)
    // 요청: 관리자, 기간 없음
    // 확인: 422이고 오류 목록에 from, to가 나오는지 (후보 테이블은 커서 기간 없이 조회하지 못하게 막음)
    @Test
    void 기간을_빼고_조회하면_422이고_from과_to가_오류로_나온다() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/admin/payouts/candidates")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[*].path").value(containsInAnyOrder("from", "to")));
    }

    // ---------- 역할 확인 (@PreAuthorize) ----------

    // API: GET /api/v1/admin/payouts/candidates (관리자 정산후보 조회)
    // 요청: 판매자 7 토큰
    // 확인: 403인지 (관리자 API는 판매자가 쓸 수 없음)
    @Test
    void 판매자가_관리자_후보_API를_부르면_403이다() throws Exception {
        // when & then
        mockMvc.perform(adminRequest(SELLER_7, "BUYER", "SELLER"))
                .andExpect(status().isForbidden());
    }

    // API: GET /api/v1/payouts/me/candidates (판매자 본인 정산후보 조회)
    // 요청: 관리자 토큰
    // 확인: 403인지 (관리자는 관리자 API로 조회함)
    @Test
    void 관리자가_판매자_후보_API를_부르면_403이다() throws Exception {
        // when & then
        mockMvc.perform(sellerRequest(ADMIN_ID, "ADMIN"))
                .andExpect(status().isForbidden());
    }

    // API: GET /api/v1/payouts/me/candidates (판매자 본인 정산후보 조회)
    // 요청: 구매자 토큰
    // 확인: 403인지 (정산후보는 판매자만 있음)
    @Test
    void 구매자가_판매자_후보_API를_부르면_403이다() throws Exception {
        // when & then
        mockMvc.perform(sellerRequest(BUYER_3, "BUYER"))
                .andExpect(status().isForbidden());
    }

    // API: GET /api/v1/admin/payouts/candidates (관리자 정산후보 조회)
    // 요청: 로그인 토큰 없음, 기간은 올바름
    // 확인: 401(로그인 필요)인지
    @Test
    void 관리자_후보_API를_토큰_없이_부르면_401이다() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/admin/payouts/candidates")
                        .param("from", listFrom.toString())
                        .param("to", listTo.toString()))
                .andExpect(status().isUnauthorized());
    }

    // API: GET /api/v1/payouts/me/candidates (판매자 본인 정산후보 조회)
    // 요청: 로그인 토큰 없음, 기간은 올바름
    // 확인: 401(로그인 필요)인지
    @Test
    void 판매자_후보_API를_토큰_없이_부르면_401이다() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/payouts/me/candidates")
                        .param("from", listFrom.toString())
                        .param("to", listTo.toString()))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 경로 ----------

    // API: GET /api/v1/payouts/me/candidates (판매자 본인 정산후보 조회)
    // 요청: 판매자 7, 기간 없음
    // 확인: 422이고 오류 목록에 from, to가 나오는지
    //       /candidates가 정산 상세(/payouts/me/{payoutId})로 잘못 들어가면 "정산 ID가 숫자가 아님" 400이 나오므로,
    //       422(후보 조회의 기간 검증)가 나오면 후보 조회 API로 제대로 들어간 것
    @Test
    void 후보_경로는_정산_상세_경로로_잘못_들어가지_않는다() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/payouts/me/candidates")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_7, "BUYER", "SELLER")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[*].path").value(containsInAnyOrder("from", "to")));
    }

    // ---------- 도우미: 요청 ----------
    // roles는 토큰에 넣을 권한 목록 (관리자 "ADMIN", 판매자 "BUYER", "SELLER" — 실제 토큰 모양)
    // 토큰을 인자로 받는 이유: 판매자 토큰으로 관리자 API를 부르는 것처럼 역할을 바꿔 끼운 요청도 만들려고

    private MockHttpServletRequestBuilder adminRequest(long memberId, String... roles) {
        return get("/api/v1/admin/payouts/candidates")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, roles))
                .param("from", listFrom.toString())
                .param("to", listTo.toString());
    }

    private MockHttpServletRequestBuilder sellerRequest(long memberId, String... roles) {
        return get("/api/v1/payouts/me/candidates")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, roles))
                .param("from", listFrom.toString())
                .param("to", listTo.toString());
    }

    // 응답 JSON과 같은 시각 형식으로 바꿈 (LocalDateTime.toString()은 초가 0이면 초를 빼서 응답과 모양이 다름)
    private String json(LocalDateTime time) {
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(time);
    }

    // ---------- 도우미: 배치 결과 ----------

    // 배치가 만든 정산 중 판매자 7 정산과 시스템 몫 정산의 ID를 기억해 둠 (응답의 payoutId 비교용, JPA 조회)
    // 테스트마다 테이블을 비우고 배치를 한 번 돌리므로 스냅샷은 항상 1건
    private void rememberPayoutIds() {
        Long snapshotId = snapshotRepository.findAll().get(0).getId();
        for (Payout payout : payoutRepository.findBySnapshotId(snapshotId)) {
            if (payout.isSystem()) {
                systemPayoutId = payout.getId();
            } else if (payout.getPayeeId().equals(SELLER_7)) {
                seller7PayoutId = payout.getId();
            }
        }
    }

    // ---------- 도우미: 데이터 준비·정리 (JDBC) ----------
    // PayoutQueryIntegrationTest와 같은 방식 (그쪽은 private이라 복사). 차이는 생성 시각을 인자로 받는 것과 주문항목 번호대

    // 후보의 주문에 지갑 차감 기록을 넣음 (없으면 validateStep이 DEDUCTION_MISSING으로 빼서 정산되지 않음, #194)
    // 같은 주문에 후보가 여러 건(대금·수수료·조정)이라 주문당 1건만 넣음 (UK order_id)
    // 금액은 지금 검사하지 않아(#194는 기록 유무만 봄) 넉넉한 고정값. 금액 검사(#195)가 생기면 실제 합계로 바꿔야 함
    private void givenDeduction(long orderId) {
        if (payoutWalletDeductionRepository.existsByOrderId(orderId)) {
            return;
        }
        payoutWalletDeductionRepository.save(
                PayoutWalletDeduction.create(orderId, 1L, 1_000_000L, LocalDateTime.now()));
    }

    // 정산후보를 원하는 created_at으로 직접 저장
    // 이 테스트는 상품 하나 = 주문 하나로 단순화해서 주문 ID에 주문항목 ID와 같은 값을 씀
    private void insertCandidate(PayoutEventType eventType, long orderItemId, Long sellerId, Long amount,
                                 LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                eventType.name(), orderItemId, orderItemId, sellerId, amount, createdAt, createdAt);
        givenDeduction(orderItemId);
    }

    // 관리자 수동 조정 후보를 원하는 created_at으로 직접 저장
    // 주문 ID는 insertCandidate와 같은 규칙 (주문항목 ID와 같은 값)
    private void insertAdjustment(PayoutEventType eventType, long orderItemId, Long sellerId, Long amount,
                                  long adjustmentId, long adminId, String reason, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items " +
                        "(event_type, order_id, order_item_id, seller_id, amount, adjustment_id, admin_id, reason, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventType.name(), orderItemId, orderItemId, sellerId, amount, adjustmentId, adminId, reason,
                createdAt, createdAt);
        givenDeduction(orderItemId);
    }

    private long nextOrderItemId() {
        return ORDER_ITEM_ID_SEQ.incrementAndGet();
    }

    // 정산 결과는 정산 테스트들만 만들고 "마지막 정산일"이 배치 기간 계산에 쓰이므로 전부 비움
    // 정산후보는 이 클래스 번호대만 지움 (다른 테스트의 후보를 건드리지 않도록)
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
        jdbcTemplate.update("DELETE FROM payout_candidate_items WHERE order_id > ? AND order_id <= ?",
                ORDER_ITEM_ID_START, ORDER_ITEM_ID_END);
        // 지갑 차감 기록도 이 클래스 번호대만 지움 (후보를 넣을 때 같은 주문 ID로 함께 넣음)
        jdbcTemplate.update("DELETE FROM payout_wallet_deduction WHERE order_id > ? AND order_id <= ?",
                ORDER_ITEM_ID_START, ORDER_ITEM_ID_END);
    }
}
