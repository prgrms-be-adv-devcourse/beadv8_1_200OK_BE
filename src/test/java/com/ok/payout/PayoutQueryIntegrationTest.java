package com.ok.payout;

import com.jayway.jsonpath.JsonPath;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.repository.PayoutRepository;
import com.ok.payout.repository.PayoutSnapshotRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 통합 테스트: 정산 조회 API(목록·상세·항목)를 실제 HTTP 요청(MockMvc)과 MySQL 컨테이너로 확인
// - 정산 데이터는 정산후보만 넣고 실제 정산 배치를 돌려 만든다 (운영과 같은 구조로 확인하기 위해)
// - 정산후보는 created_at을 과거로 넣어야 하므로 SQL로 직접 넣는다 (저장 시 Auditing이 현재 시각으로 덮어쓰기 때문)
// - SQL로 넣는 시각은 변환 없이 그대로 넣는다. 테스트 JVM(build.gradle.kts)과 Hibernate 시간대(application-test.yml)가
//   모두 Asia/Seoul로 고정되어(#93), JPA가 저장·조회하는 시각과 기준이 같기 때문이다
// - 정산 확정 시각도 SQL로 고정값을 넣는다. 실제 배치 시각은 마이크로초 단위로 달라 정렬·기간 결과를 미리 알 수 없고,
//   Payout은 @Immutable이고 created_at은 updatable = false라 JPA로는 바꿀 수 없음
// - 결과 확인은 API 응답과 JPA 리포지토리로 한다. JDBC는 데이터 준비·정리에만 쓴다
// - 데이터 준비·정리 도우미는 PayoutSettleIntegrationTest와 같은 방식 (그쪽은 private이라 복사)
//
// [이 테스트를 읽는 방법]
// Postman으로 API를 호출하고 응답 JSON을 눈으로 확인하는 일을 코드로 자동으로 한다고 보면 된다.
// 테스트마다 위에 "API / 요청 / 확인" 세 줄로 무엇을 부르고 무엇을 보는지 적어 둠
//
//   mockMvc.perform(요청)                       ← API를 실제로 호출 (서버를 띄우지 않고 컨트롤러 → 서비스 → DB까지 실제 코드가 돎)
//           .andExpect(status().isOk())        ← 응답 코드가 200인지 (403, 422 등이면 실패)
//           .andExpect(jsonPath("주소").value(기대값))   ← 응답 JSON에서 "주소"의 값을 꺼내 기대값과 같은지
//
// jsonPath 주소 읽는 법 (응답 예: {"content":[{"payoutId":14},{"payoutId":13}], "page":0})
//   $                       JSON 맨 바깥
//   $.page                  바깥의 page              → 0
//   $.content[0].payoutId   content 첫 번째의 payoutId → 14
//   $.content[*].payoutId   content 모든 칸의 payoutId → [14, 13]   ([*] = 배열의 모든 칸)
//   $.content.length()      content 배열의 칸 수 (몇 건 나왔는지)
//   $.content[?(@.eventType == 'SALE_SHIPPING_FEE')].amount
//                           content 중 조건에 맞는 칸의 amount → [3000]   ([?(조건)] = 조건에 맞는 칸만, @ = 각 칸)
//                           조건으로 고르면 결과가 항상 배열이라 contains(...)로 비교
//
// 자주 쓰는 비교
//   contains(a, b, c)            정확히 이 값들이, 정확히 이 순서로 (순서·개수가 다르면 실패)
//   containsInAnyOrder(a, b)     이 값들이 모두 있으면 순서는 상관없음
//   nullValue()                  값이 null
//   .intValue()                  JSON의 작은 숫자는 Integer로 읽혀서, Long 변수와 비교할 때 타입을 맞춤
//
// 예) 아래 첫 번째 테스트를 풀어 읽으면
//   관리자로 10월 1일 정산 목록을 요청하면 → 200 OK가 오고
//   → 정산 3건이 시스템 몫, 판매자 9, 판매자 7 순서로 나오고 → 0페이지, 크기 20, 전체 3건, 1페이지여야 한다
class PayoutQueryIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 주문항목 ID 번호대 (PayoutSettleIntegrationTest는 8_000_000, PayoutCandidateIntegrationTest는 9_000_000부터)
    private static final long ORDER_ITEM_ID_START = 6_000_000L;
    private static final long ORDER_ITEM_ID_END = 7_000_000L;
    private static final AtomicLong ORDER_ITEM_ID_SEQ = new AtomicLong(ORDER_ITEM_ID_START);

    private static final long ADMIN_ID = 1L;
    private static final long SELLER_7 = 7L;
    private static final long SELLER_9 = 9L;

    // 정산 확정 시각 고정값 (시스템 몫이 가장 늦음 → 최신순이면 시스템, 판매자 9, 판매자 7 순서)
    private static final LocalDateTime CONFIRMED_SELLER_7 = LocalDateTime.of(2026, 10, 1, 10, 0);
    private static final LocalDateTime CONFIRMED_SELLER_9 = LocalDateTime.of(2026, 10, 1, 11, 0);
    private static final LocalDateTime CONFIRMED_SYSTEM = LocalDateTime.of(2026, 10, 1, 12, 0);
    // 목록 조회 기본 기간: 세 정산이 모두 들어가는 10월 1일 하루
    private static final LocalDateTime LIST_FROM = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime LIST_TO = LocalDateTime.of(2026, 10, 2, 0, 0);
    // 테스트 데이터에 없는 정산 ID (테스트마다 정산이 3건뿐이라 이 번호까지 갈 일이 없음)
    private static final long NOT_EXISTING_PAYOUT_ID = 999_999L;

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
    private JdbcTemplate jdbcTemplate;

    // 배치가 만든 정산 ID (DB가 매기므로 배치 후 조회해서 채움)
    private Long seller7PayoutId;
    private Long seller9PayoutId;
    private Long systemPayoutId;

    // 정산후보 생성 시각 (초 단위로 잘라 넣어야 API 응답과 정확히 비교할 수 있음)
    private LocalDateTime candidateCreatedAt;

    // 판매자 7의 주문 항목 번호 (항목 조회에서 orderItemId 확인용)
    private long orderItemA;

    // 정산후보를 넣고 실제 정산 배치를 돌려 정산 3건을 만든 뒤, 확정 시각을 고정값으로 맞춤
    //   판매자 7: 판매 대금 9,700 + 배송비 3,000 + 조정 -2,000 = 10,700 (수수료 300은 시스템 몫으로)
    //   판매자 9: 판매 대금 19,400 (수수료 600은 시스템 몫으로)
    //   시스템 몫: 수수료 300 + 600 = 900
    @BeforeEach
    void setUp() throws Exception {
        // 보안 필터를 적용해야 토큰 검증과 401·403 처리가 실제와 같게 동작한다 (DEVELOPMENT.md "테스트에서 토큰 보내기")
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        candidateCreatedAt = LocalDateTime.now().minusDays(20).truncatedTo(ChronoUnit.SECONDS);

        orderItemA = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemA, SELLER_7, 9_700L);
        insertCandidate(PayoutEventType.SALE_FEE, orderItemA, SELLER_7, 300L);
        insertCandidate(PayoutEventType.SALE_SHIPPING_FEE, orderItemA, SELLER_7, 3_000L);
        insertAdjustment(PayoutEventType.SALE_AMOUNT, orderItemA, SELLER_7, -2_000L, 4L, ADMIN_ID, "금액 오입력 정정");

        long orderItemB = nextOrderItemId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderItemB, SELLER_9, 19_400L);
        insertCandidate(PayoutEventType.SALE_FEE, orderItemB, SELLER_9, 600L);

        JobExecution execution = payoutJobRunner.run();
        // 준비 단계 확인: 배치가 실패하면 아래 테스트가 모두 엉뚱한 이유로 실패하므로 여기서 바로 알림
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        rememberPayoutIds();
        setConfirmedAt(seller7PayoutId, CONFIRMED_SELLER_7);
        setConfirmedAt(seller9PayoutId, CONFIRMED_SELLER_9);
        setConfirmedAt(systemPayoutId, CONFIRMED_SYSTEM);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ---------- 목록 조회 ----------

    // API: GET /api/v1/payouts (정산 목록 조회)
    // 요청: 관리자, payeeId 없음, 기간 10/01 하루
    // 확인: 3건이 최신순(시스템 → 판매자 9 → 판매자 7)으로 나오고 페이지 정보가 맞는지
    @Test
    void 관리자는_payeeId_없이_기간_안의_정산을_모두_최신순으로_조회한다() throws Exception {
        // when & then: 확정 시각 시스템 12시 → 판매자 9 11시 → 판매자 7 10시
        mockMvc.perform(listRequest(ADMIN_ID, "ADMIN"))           // GET /api/v1/payouts?from=10/01&to=10/02, 관리자 토큰
                .andExpect(status().isOk())                      // 200
                .andExpect(jsonPath("$.content[*].payoutId")     // 나온 정산 ID들이
                        .value(contains(systemPayoutId.intValue(), seller9PayoutId.intValue(), seller7PayoutId.intValue())))
                                                                 // 정확히 시스템 → 판매자 9 → 판매자 7 순서
                .andExpect(jsonPath("$.page").value(0))          // 0번째 페이지
                .andExpect(jsonPath("$.size").value(20))         // size를 안 보냈으니 기본값 20
                .andExpect(jsonPath("$.totalElements").value(3)) // 조건에 맞는 전체 건수
                .andExpect(jsonPath("$.totalPages").value(1));   // 3건 ÷ 20 → 1페이지
    }

    // API: GET /api/v1/payouts (정산 목록 조회)
    // 요청: 관리자, payeeId=9, 기간 10/01 하루
    // 확인: payeeId 필터가 걸려 판매자 9 정산만 나오는지
    @Test
    void 관리자가_payeeId로_거르면_그_판매자_정산만_나온다() throws Exception {
        // when & then
        mockMvc.perform(listRequest(ADMIN_ID, "ADMIN").param("payeeId", String.valueOf(SELLER_9)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].payoutId").value(contains(seller9PayoutId.intValue())));
    }

    // API: GET /api/v1/payouts (정산 목록 조회)
    // 요청: 관리자, system=true, 기간 10/01 하루
    // 확인: 시스템 몫만 나오고 payeeId가 null인지
    @Test
    void 관리자가_system_true로_거르면_시스템_몫만_나온다() throws Exception {
        // when & then
        mockMvc.perform(listRequest(ADMIN_ID, "ADMIN").param("system", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].payoutId").value(contains(systemPayoutId.intValue())))
                .andExpect(jsonPath("$.content[0].payeeId").value(nullValue()))
                .andExpect(jsonPath("$.content[0].system").value(true));
    }

    // API: GET /api/v1/payouts (정산 목록 조회)
    // 요청: 판매자 7, payeeId=7, 기간 10/01 하루
    // 확인: 본인 정산 1건만 나오고 응답 필드(ID, 스냅샷, 수취인, 시스템 여부, 금액, 확정 시각)가 모두 맞는지
    @Test
    void 판매자는_본인_ID로_본인_정산만_조회하고_응답_필드가_모두_채워진다() throws Exception {
        // when & then
        mockMvc.perform(listRequest(SELLER_7, "BUYER", "SELLER").param("payeeId", String.valueOf(SELLER_7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].payoutId").value(seller7PayoutId.intValue()))
                .andExpect(jsonPath("$.content[0].snapshotId").value(snapshotId().intValue()))
                .andExpect(jsonPath("$.content[0].payeeId").value((int) SELLER_7))
                .andExpect(jsonPath("$.content[0].system").value(false))
                .andExpect(jsonPath("$.content[0].amount").value(10_700))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-10-01T10:00:00"));
    }

    // API: GET /api/v1/payouts (정산 목록 조회)
    // 요청: 판매자 7, payeeId 없음, 기간 10/01 하루
    // 확인: 403으로 거절되는지 (판매자는 본인 ID를 반드시 넣어야 함)
    @Test
    void 판매자가_payeeId_없이_목록을_조회하면_403이다() throws Exception {
        // when & then
        mockMvc.perform(listRequest(SELLER_7, "BUYER", "SELLER"))
                .andExpect(status().isForbidden());
    }

    // API: GET /api/v1/payouts (정산 목록 조회)
    // 요청: 관리자, 기간 10/01 11:00 ~ 12:00
    // 확인: from과 같은 시각(11시)은 포함, to와 같은 시각(12시)은 제외되는지
    @Test
    void 기간은_from과_같은_시각을_포함하고_to와_같은_시각은_제외한다() throws Exception {
        // given: from = 판매자 9 확정 시각(11시), to = 시스템 몫 확정 시각(12시)
        // when & then: 판매자 9(from과 같음, 포함)만 나오고, 시스템 몫(to와 같음)과 판매자 7(from보다 이른 10시)은 제외
        mockMvc.perform(get("/api/v1/payouts")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN"))
                        .param("from", CONFIRMED_SELLER_9.toString())
                        .param("to", CONFIRMED_SYSTEM.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].payoutId").value(contains(seller9PayoutId.intValue())));
    }

    // API: GET /api/v1/payouts (정산 목록 조회)
    // 요청: 관리자, 기간 10/01 하루 (판매자 7·9 정산의 확정 시각을 둘 다 11시로 맞춤)
    // 확인: 확정 시각이 같으면 정산 ID가 큰 것부터 나오는지
    @Test
    void 확정_시각이_같으면_정산_ID가_큰_것부터_나온다() throws Exception {
        // given: 판매자 7 정산의 확정 시각을 판매자 9와 같은 11시로 맞춤
        setConfirmedAt(seller7PayoutId, CONFIRMED_SELLER_9);
        long biggerId = Math.max(seller7PayoutId, seller9PayoutId);
        long smallerId = Math.min(seller7PayoutId, seller9PayoutId);

        // when & then: 시스템 몫(12시) 다음은 같은 11시끼리 ID 역순
        mockMvc.perform(listRequest(ADMIN_ID, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].payoutId")
                        .value(contains(systemPayoutId.intValue(), (int) biggerId, (int) smallerId)));
    }

    // API: GET /api/v1/payouts (정산 목록 조회)
    // 요청: 관리자, size=2로 0페이지와 1페이지
    // 확인: 페이지가 나뉘어도 전체 건수는 3이고, 0페이지 2건·1페이지 1건이 순서대로 나오는지
    @Test
    void size만큼_나눠서_조회하고_전체_건수는_그대로다() throws Exception {
        // when & then: 0페이지 2건(시스템, 판매자 9)
        mockMvc.perform(listRequest(ADMIN_ID, "ADMIN").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].payoutId")
                        .value(contains(systemPayoutId.intValue(), seller9PayoutId.intValue())))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        // when & then: 1페이지 1건(판매자 7)
        mockMvc.perform(listRequest(ADMIN_ID, "ADMIN").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].payoutId").value(contains(seller7PayoutId.intValue())));
    }

    // API: GET /api/v1/payouts (정산 목록 조회)
    // 요청: 관리자, 기간 없음
    // 확인: 422이고 오류 목록에 from, to가 나오는지
    @Test
    void 기간을_빼고_조회하면_422이고_from과_to가_오류로_나온다() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/payouts")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[*].path").value(containsInAnyOrder("from", "to")));
    }

    // API: GET /api/v1/payouts (정산 목록 조회)
    // 요청: 관리자, 기간 2026-01-01 00:00:00 ~ 2027-01-01 00:00:01 (1년 1초)
    // 확인: 422이고 오류 목록에 periodWithinMaxRange가 나오는지
    @Test
    void 기간이_1년을_넘으면_422이다() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/payouts")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN"))
                        .param("from", "2026-01-01T00:00:00")
                        .param("to", "2027-01-01T00:00:01"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[*].path").value(contains("periodWithinMaxRange")));
    }

    // API: GET /api/v1/payouts (정산 목록 조회)
    // 요청: 관리자, size=101
    // 확인: 422이고 오류 목록에 size가 나오는지
    @Test
    void 페이지_크기가_100을_넘으면_422이다() throws Exception {
        // when & then
        mockMvc.perform(listRequest(ADMIN_ID, "ADMIN").param("size", "101"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[*].path").value(contains("size")));
    }

    // API: GET /api/v1/payouts (정산 목록 조회)
    // 요청: 로그인 토큰(Authorization 헤더) 없음, 기간은 올바름
    // 확인: 401(로그인 필요)인지
    @Test
    void 토큰이_없으면_401이다() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/payouts")
                        .param("from", LIST_FROM.toString())
                        .param("to", LIST_TO.toString()))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 상세 조회 ----------

    // API: GET /api/v1/payouts/{payoutId} (정산 상세 조회)
    // 요청: 판매자 7, 본인 정산(판매자 7 정산)
    // 확인: 정산 필드, 정산 대상 기간(스냅샷), 항목 수 3, 본인 수수료 300원·1건, 시스템 몫 정산 ID는 null
    @Test
    void 판매자가_본인_정산_상세를_보면_기간_항목_수_본인_수수료를_받는다() throws Exception {
        // given: 비교할 정산 대상 기간은 고정값이 아니라 배치가 만든 스냅샷에서 꺼냄
        //   - 시작(periodFrom): 테스트마다 정산 테이블을 비우고 처음 돌리는 배치라 "이전 정산이 없음" → null
        //   - 끝(periodTo): 배치 실행일 기준으로 계산되므로(실행일 − 대기 일수 0시) 날짜가 테스트를 돌리는 날마다 달라짐
        // 주의: 정산 대상 기간을 정하는 규칙(PayoutService.settlementFrom/settlementTo)이 바뀌면 이 기대값도 함께 바뀜
        //   예) 실패한 회차를 다시 처리하는 기능(#67)이 들어와 시작·끝 계산에 실패 기간이 반영되면,
        //       첫 실행의 시작이 null이 아니게 되거나 끝 계산 기준이 달라질 수 있으니 이 테스트부터 다시 확인할 것
        PayoutSnapshot snapshot = snapshotRepository.findAll().get(0);

        // when & then
        mockMvc.perform(detailRequest(seller7PayoutId, SELLER_7, "BUYER", "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutId").value(seller7PayoutId.intValue()))
                .andExpect(jsonPath("$.snapshotId").value(snapshot.getId().intValue()))
                .andExpect(jsonPath("$.payeeId").value((int) SELLER_7))
                .andExpect(jsonPath("$.system").value(false))
                .andExpect(jsonPath("$.amount").value(10_700))
                .andExpect(jsonPath("$.createdAt").value("2026-10-01T10:00:00"))
                .andExpect(jsonPath("$.periodFrom").value(nullValue()))
                .andExpect(jsonPath("$.periodTo").value(json(snapshot.getPeriodTo())))
                .andExpect(jsonPath("$.itemCount").value(3))                 // 판매 대금, 배송비, 조정
                .andExpect(jsonPath("$.sellerFeeAmount").value(300))          // 시스템 몫에서 판매자 7 것만
                .andExpect(jsonPath("$.sellerFeeItemCount").value(1))
                .andExpect(jsonPath("$.systemPayoutId").value(nullValue()));  // 판매자에게는 숨김
    }

    // API: GET /api/v1/payouts/{payoutId} (정산 상세 조회)
    // 요청: 관리자, 판매자 7 정산
    // 확인: 판매자 수수료와 함께 시스템 몫 정산 ID도 받는지
    @Test
    void 관리자가_판매자_정산_상세를_보면_시스템_몫_정산_ID도_받는다() throws Exception {
        // when & then
        mockMvc.perform(detailRequest(seller7PayoutId, ADMIN_ID, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemPayoutId").value(systemPayoutId.intValue()))
                .andExpect(jsonPath("$.sellerFeeAmount").value(300))
                .andExpect(jsonPath("$.sellerFeeItemCount").value(1));
    }

    // API: GET /api/v1/payouts/{payoutId} (정산 상세 조회)
    // 요청: 관리자, 시스템 몫 정산
    // 확인: 금액 900·항목 수 2이고, 판매자 수수료 필드 3개는 null인지
    //       시스템 몫 정산은 특정 판매자의 정산이 아니라 이번 회차 수수료를 모두 모은 총합이라 "이 판매자의 수수료"가 없음
    //       총합은 amount(900)·itemCount(2)에 있고, 판매자별 수수료(7: 300, 9: 600)는 항목 조회의 sellerId로 봄
    @Test
    void 시스템_몫_정산_상세에는_판매자_수수료_필드가_없다() throws Exception {
        // when & then
        mockMvc.perform(detailRequest(systemPayoutId, ADMIN_ID, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system").value(true))
                .andExpect(jsonPath("$.payeeId").value(nullValue()))
                .andExpect(jsonPath("$.amount").value(900))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.systemPayoutId").value(nullValue()))
                .andExpect(jsonPath("$.sellerFeeAmount").value(nullValue()))
                .andExpect(jsonPath("$.sellerFeeItemCount").value(nullValue()));
    }

    // API: GET /api/v1/payouts/{payoutId} (정산 상세 조회)
    // 요청: 관리자, 없는 정산 ID
    // 확인: 404인지
    @Test
    void 없는_정산_상세를_보면_404이다() throws Exception {
        // when & then
        mockMvc.perform(detailRequest(NOT_EXISTING_PAYOUT_ID, ADMIN_ID, "ADMIN"))
                .andExpect(status().isNotFound());
    }

    // API: GET /api/v1/payouts/{payoutId} (정산 상세 조회)
    // 요청: 판매자 7, 판매자 9의 정산
    // 확인: 403인지 (URL의 정산 ID는 아무 번호나 넣을 수 있어서 정산 단위로 막음)
    @Test
    void 판매자가_다른_판매자_정산_상세를_보면_403이다() throws Exception {
        // when & then
        mockMvc.perform(detailRequest(seller9PayoutId, SELLER_7, "BUYER", "SELLER"))
                .andExpect(status().isForbidden());
    }

    // ---------- 항목 조회 ----------

    // API: GET /api/v1/payouts/{payoutId}/items (정산 항목 조회)
    // 요청: 관리자, 판매자 7 정산
    // 확인: 정산후보와 조인한 결과 3개가 항목 ID 오름차순으로 나오고,
    //       판매 대금·배송비·조정 각각의 금액·주문 항목·후보 생성 시각과, 조정 건의 조정 번호·관리자 ID·사유가 맞는지
    @Test
    void 관리자는_항목을_후보_생성_시각과_조정_정보와_함께_ID_순으로_받는다() throws Exception {
        // when
        String body = mockMvc.perform(itemsRequest(seller7PayoutId, ADMIN_ID, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                // 모든 항목 공통: 주문 항목 A, 판매자 7, 후보 생성 시각
                .andExpect(jsonPath("$.content[*].orderItemId")
                        .value(contains((int) orderItemA, (int) orderItemA, (int) orderItemA)))
                .andExpect(jsonPath("$.content[*].sellerId").value(contains(7, 7, 7)))
                .andExpect(jsonPath("$.content[*].candidateCreatedAt")
                        .value(contains(json(candidateCreatedAt), json(candidateCreatedAt), json(candidateCreatedAt))))
                // 항목 종류·조정 여부로 각 항목을 찾아서 확인 (저장 순서와 상관없이)
                .andExpect(jsonPath("$.content[?(@.eventType == 'SALE_AMOUNT' && @.adjustment == null)].amount")
                        .value(contains(9_700)))
                .andExpect(jsonPath("$.content[?(@.eventType == 'SALE_SHIPPING_FEE')].amount")
                        .value(contains(3_000)))
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].amount").value(contains(-2_000)))
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].eventType").value(contains("SALE_AMOUNT")))
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].adjustment.adjustmentId").value(contains(4)))
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].adjustment.adminId").value(contains((int) ADMIN_ID)))
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].adjustment.reason")
                        .value(contains("금액 오입력 정정")))
                .andReturn().getResponse().getContentAsString();

        // then: 항목 ID가 오름차순인지 (어떤 항목이 몇 번째인지는 보지 않고 정렬만 확인)
        List<Integer> payoutItemIds = JsonPath.read(body, "$.content[*].payoutItemId");
        assertThat(payoutItemIds).isSorted();
    }

    // API: GET /api/v1/payouts/{payoutId}/items (정산 항목 조회)
    // 요청: 판매자 7, 본인 정산
    // 확인: 조정 건에 사유만 있고 조정 번호·관리자 ID는 null인지
    @Test
    void 판매자가_항목을_보면_조정_건에_사유만_있다() throws Exception {
        // when & then: 조정 정보가 있는 칸을 찾아서 확인
        mockMvc.perform(itemsRequest(seller7PayoutId, SELLER_7, "BUYER", "SELLER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].adjustment.reason")
                        .value(contains("금액 오입력 정정")))
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].adjustment.adjustmentId")
                        .value(contains(nullValue())))
                .andExpect(jsonPath("$.content[?(@.adjustment != null)].adjustment.adminId")
                        .value(contains(nullValue())));
    }

    // API: GET /api/v1/payouts/{payoutId}/items (정산 항목 조회)
    // 요청: 관리자, 판매자 7 정산, size=2로 0페이지와 1페이지
    // 확인: 0페이지 2개·1페이지 1개, 전체 3개·2페이지이고, 두 페이지에 같은 항목이 겹쳐 나오지 않는지
    @Test
    void 항목을_size만큼_나눠서_조회한다() throws Exception {
        // when: 0페이지
        String page0 = mockMvc.perform(itemsRequest(seller7PayoutId, ADMIN_ID, "ADMIN").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andReturn().getResponse().getContentAsString();

        // when: 1페이지
        String page1 = mockMvc.perform(itemsRequest(seller7PayoutId, ADMIN_ID, "ADMIN").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        // then: 두 페이지의 항목 ID를 합치면 서로 다른 3개
        List<Integer> allIds = new ArrayList<>();
        allIds.addAll(JsonPath.read(page0, "$.content[*].payoutItemId"));
        allIds.addAll(JsonPath.read(page1, "$.content[*].payoutItemId"));
        assertThat(allIds).hasSize(3).doesNotHaveDuplicates();
    }

    // API: GET /api/v1/payouts/{payoutId}/items (정산 항목 조회)
    // 요청: 관리자, 없는 정산 ID
    // 확인: 404인지
    @Test
    void 없는_정산의_항목을_보면_404이다() throws Exception {
        // when & then
        mockMvc.perform(itemsRequest(NOT_EXISTING_PAYOUT_ID, ADMIN_ID, "ADMIN"))
                .andExpect(status().isNotFound());
    }

    // API: GET /api/v1/payouts/{payoutId}/items (정산 항목 조회)
    // 요청: 판매자 7, 판매자 9의 정산
    // 확인: 403인지
    @Test
    void 판매자가_다른_판매자_정산_항목을_보면_403이다() throws Exception {
        // when & then
        mockMvc.perform(itemsRequest(seller9PayoutId, SELLER_7, "BUYER", "SELLER"))
                .andExpect(status().isForbidden());
    }

    // ---------- 도우미: 요청 ----------

    // 요청 도우미의 roles는 토큰에 넣을 권한 목록 (관리자 "ADMIN", 판매자 "BUYER", "SELLER" — 실제 토큰 모양)

    // 목록 조회 기본 요청 (로그인 토큰 + 10월 1일 하루 기간)
    private MockHttpServletRequestBuilder listRequest(long memberId, String... roles) {
        return get("/api/v1/payouts")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, roles))
                .param("from", LIST_FROM.toString())
                .param("to", LIST_TO.toString());
    }

    private MockHttpServletRequestBuilder detailRequest(long payoutId, long memberId, String... roles) {
        return get("/api/v1/payouts/{payoutId}", payoutId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, roles));
    }

    private MockHttpServletRequestBuilder itemsRequest(long payoutId, long memberId, String... roles) {
        return get("/api/v1/payouts/{payoutId}/items", payoutId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, roles));
    }

    // 응답 JSON과 같은 시각 형식으로 바꿈 (LocalDateTime.toString()은 초가 0이면 초를 빼서 응답과 모양이 다름)
    private String json(LocalDateTime time) {
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(time);
    }

    // ---------- 도우미: 배치 결과 ----------

    // 배치가 만든 정산 3건의 ID를 기억해 둠 (판매자 7, 판매자 9, 시스템 몫)
    private void rememberPayoutIds() {
        for (Payout payout : payoutRepository.findBySnapshotId(snapshotId())) {
            if (payout.isSystem()) {
                systemPayoutId = payout.getId();
            } else if (payout.getPayeeId().equals(SELLER_7)) {
                seller7PayoutId = payout.getId();
            } else if (payout.getPayeeId().equals(SELLER_9)) {
                seller9PayoutId = payout.getId();
            }
        }
    }

    // 테스트마다 테이블을 비우고 배치를 한 번 돌리므로 스냅샷은 항상 1건
    private Long snapshotId() {
        return snapshotRepository.findAll().get(0).getId();
    }

    // ---------- 도우미: 데이터 준비·정리 (JDBC) ----------

    // 정산후보를 20일 전 시각으로 직접 저장
    private void insertCandidate(PayoutEventType eventType, long orderItemId, Long sellerId, Long amount) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_item_id, seller_id, amount, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                eventType.name(), orderItemId, sellerId, amount, candidateCreatedAt, candidateCreatedAt);
    }

    // 관리자 수동 조정 후보를 20일 전 시각으로 직접 저장
    private void insertAdjustment(PayoutEventType eventType, long orderItemId, Long sellerId, Long amount,
                                  long adjustmentId, long adminId, String reason) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items " +
                        "(event_type, order_item_id, seller_id, amount, adjustment_id, admin_id, reason, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventType.name(), orderItemId, sellerId, amount, adjustmentId, adminId, reason, candidateCreatedAt, candidateCreatedAt);
    }

    // 정산 확정 시각을 고정값으로 바꿈 (Payout은 @Immutable·created_at은 updatable = false라 JPA로는 바꿀 수 없음)
    private void setConfirmedAt(Long payoutId, LocalDateTime confirmedAt) {
        jdbcTemplate.update("UPDATE payout SET created_at = ? WHERE id = ?", confirmedAt, payoutId);
    }

    private long nextOrderItemId() {
        return ORDER_ITEM_ID_SEQ.incrementAndGet();
    }

    // 정산 결과는 이 클래스와 PayoutSettleIntegrationTest만 만들고 "마지막 정산일"이 배치 기간 계산에 쓰이므로 전부 비움
    // 정산후보는 이 클래스 번호대만 지움 (다른 테스트의 후보를 건드리지 않도록)
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
