package com.ok.payout;

import com.ok.payout.repository.PayoutCandidateItemRepository;
import com.ok.payout.repository.PayoutRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 통합 테스트: 형태만 공개하고 막아 둔 정산 API(1건 수동 실행, 수동 조정)가 정말 막혀 있는지 실제 HTTP 요청으로 확인
//
// [임시 테스트]
// 두 API는 정책이 정해지지 않아 임시로 막아 둔 상태라, 이 테스트도 "막혀 있음"을 확인하는 임시 테스트다.
//   - 1건 수동 실행: 재처리 정책 미정 (PayoutService.runOrderItem의 TODO(논의 필요))
//   - 수동 조정: 조정 번호(adjustmentId) 발급 방식 미정 (PayoutService.createAdjustment의 TODO(논의 필요))
// API를 열면 501을 기대하는 테스트는 실제 동작(실행·조정 결과)을 확인하는 테스트로 바꿔야 하고,
// 401(로그인 필요), 403(관리자 전용)과 422(형식 검증) 테스트는 그대로 쓸 수 있다.
//
// [확인하는 것]
//   - 관리자가 부르면 501(아직 지원하지 않는 기능)이고, 데이터가 바뀌지 않음
//   - 관리자가 아니면 403(권한 없음)
//   - 수동 조정은 로그인 토큰이 없으면 401
//   - 조정 요청의 형식이 틀리면 422 (권한·501 확인보다 먼저 거절됨)
//
// [두 API의 회원 정보 받는 방식이 다름]
//   - 1건 수동 실행: 아직 임시 헤더(X-Member-Role)로 역할을 받음. 거절은 서비스가 함 → 403 type이 payout-access-denied
//   - 수동 조정: 로그인 토큰으로 받음. 거절은 컨트롤러 @PreAuthorize가 함 → 403 type이 공통 forbidden
//
// [준비]
// 정산 데이터를 미리 만들 필요가 없어 배치를 돌리지 않음 (조회 API 테스트는 PayoutQueryIntegrationTest)
// 읽는 방법(mockMvc·jsonPath)은 PayoutQueryIntegrationTest 위의 설명과 같음
//
// [요청 본문(JSON)]
// POST 요청은 값을 JSON 본문으로 보내므로 텍스트 블록(""" ... """)에 실제로 보낼 JSON을 그대로 적음
// (객체로 만들면 생성자가 공백을 지워 버려서, "공백뿐인 사유" 같은 잘못된 입력을 그대로 보낼 수 없음)
class PayoutBlockedApiIntegrationTest extends AbstractIntegrationTest {

    // 1건 수동 실행만 쓰는 임시 역할 헤더 (수동 조정은 토큰으로 바뀜)
    private static final String MEMBER_ROLE_HEADER = "X-Member-Role";
    private static final long ADMIN_ID = 1L;
    private static final long SELLER_7 = 7L;

    // 요청에 쓰는 주문 항목 번호. 다른 테스트의 번호대(6_000_000 ~ 9_000_000대)와 겹치지 않게 정함
    // 주의: 아래 JSON 본문에도 5000001을 그대로 적었으므로, 이 값을 바꾸면 JSON 안의 번호도 함께 바꿀 것
    private static final long ORDER_ITEM_ID = 5_000_001L;

    // 오류 응답의 type 끝부분 (모듈 이름 + 에러 코드 이름을 소문자·하이픈으로 바꾼 값)
    // type 앞부분(주소)은 설정(PROBLEM_BASE_URI)마다 달라서 끝부분만 비교
    private static final String FEATURE_NOT_SUPPORTED_TYPE = "/payout/feature-not-supported";
    // 1건 수동 실행의 403: 서비스가 PayoutErrorCode로 거절
    private static final String ACCESS_DENIED_TYPE = "/payout/payout-access-denied";
    // 수동 조정의 403: @PreAuthorize가 거절하고 공통 처리기(ApiControllerAdvice)가 CommonErrorCode.FORBIDDEN으로 응답
    private static final String FORBIDDEN_TYPE = "/forbidden";

    // 형식이 올바른 조정 요청 (주문 항목 5000001에서 2,000원 차감)
    private static final String VALID_ADJUSTMENT_BODY = """
            {"orderItemId": 5000001, "eventType": "SALE_AMOUNT", "amount": -2000, "reason": "금액 오입력 정정"}
            """;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    // 요청에 붙일 로그인 토큰을 만듦 (실제 토큰과 같은 형식이라 보안 필터가 똑같이 검증함)
    @Autowired
    private TestAccessTokens tokens;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private PayoutCandidateItemRepository candidateRepository;

    // 보안 필터를 적용해야 토큰 검증과 401·403 처리가 실제와 같게 동작한다 (DEVELOPMENT.md "테스트에서 토큰 보내기")
    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ---------- 1건 수동 실행 ----------

    // API: POST /api/v1/payouts/run/order-items/{orderItemId} (정산 1건 수동 실행, 막아 둠)
    // 요청: 관리자 (역할 헤더), 주문 항목 5000001
    // 확인: 501(아직 지원하지 않는 기능)이고, 정산이 새로 생기지 않았는지
    @Test
    void 관리자가_1건_수동_실행을_요청하면_501이고_정산이_생기지_않는다() throws Exception {
        // given: 요청 전 정산 건수 (다른 테스트가 남긴 데이터가 있어도 "늘지 않았는지"만 봄)
        long payoutCountBefore = payoutRepository.count();

        // when & then
        mockMvc.perform(runOrderItemRequest("ADMIN"))
                .andExpect(status().isNotImplemented())                                  // 501
                .andExpect(jsonPath("$.type").value(endsWith(FEATURE_NOT_SUPPORTED_TYPE)));

        // then: 막아 둔 API라 정산이 만들어지면 안 됨
        assertThat(payoutRepository.count()).isEqualTo(payoutCountBefore);
    }

    // API: POST /api/v1/payouts/run/order-items/{orderItemId} (정산 1건 수동 실행, 막아 둠)
    // 요청: 판매자 (역할 헤더), 주문 항목 5000001
    // 확인: 403(권한 없음)인지 (관리자 전용 기능이라 막혀 있는지와 상관없이 판매자는 거절)
    @Test
    void 판매자가_1건_수동_실행을_요청하면_403이다() throws Exception {
        // when & then
        mockMvc.perform(runOrderItemRequest("SELLER"))
                .andExpect(status().isForbidden())                                       // 403
                .andExpect(jsonPath("$.type").value(endsWith(ACCESS_DENIED_TYPE)));
    }

    // ---------- 수동 조정 ----------

    // API: POST /api/v1/payouts/adjustments (정산 수동 조정, 막아 둠)
    // 요청: 관리자 토큰, 형식이 올바른 조정 요청 (주문 항목 5000001에서 2,000원 차감)
    // 확인: 501(아직 지원하지 않는 기능)이고, 그 주문 항목에 정산후보(조정 건)가 생기지 않았는지
    @Test
    void 관리자가_수동_조정을_요청하면_501이고_정산후보가_생기지_않는다() throws Exception {
        // when & then
        mockMvc.perform(adjustmentRequest(VALID_ADJUSTMENT_BODY)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isNotImplemented())                                  // 501
                .andExpect(jsonPath("$.type").value(endsWith(FEATURE_NOT_SUPPORTED_TYPE)));

        // then: 막아 둔 API라 조정 데이터(정산후보)가 만들어지면 안 됨
        assertThat(candidateRepository.findAllByOrderItemId(ORDER_ITEM_ID)).isEmpty();
    }

    // API: POST /api/v1/payouts/adjustments (정산 수동 조정, 막아 둠)
    // 요청: 판매자 토큰(BUYER, SELLER), 형식이 올바른 조정 요청
    // 확인: 403(권한 없음)이고 type이 공통 forbidden인지 (관리자 전용 기능, @PreAuthorize가 거절)
    @Test
    void 판매자가_수동_조정을_요청하면_403이다() throws Exception {
        // given: 본문은 VALID_ADJUSTMENT_BODY (형식 오류 422가 아니라 권한 때문에 거절되는지 보려고 형식은 올바르게)

        // when & then
        mockMvc.perform(adjustmentRequest(VALID_ADJUSTMENT_BODY)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_7, "BUYER", "SELLER")))
                .andExpect(status().isForbidden())                                       // 403
                .andExpect(jsonPath("$.type").value(endsWith(FORBIDDEN_TYPE)));
    }

    // API: POST /api/v1/payouts/adjustments (정산 수동 조정, 막아 둠)
    // 요청: 로그인 토큰 없음, 형식이 올바른 조정 요청
    // 확인: 401(로그인 필요)인지
    @Test
    void 토큰_없이_수동_조정을_요청하면_401이다() throws Exception {
        // when & then
        mockMvc.perform(adjustmentRequest(VALID_ADJUSTMENT_BODY))
                .andExpect(status().isUnauthorized());                                  // 401
    }

    // API: POST /api/v1/payouts/adjustments (정산 수동 조정, 막아 둠)
    // 요청: 판매자 토큰, 금액 0원 (형식도 틀리고 권한도 없음)
    // 확인: 403이 아니라 422인지 (요청 본문 검증이 @PreAuthorize 권한 확인보다 먼저 일어남)
    @Test
    void 판매자가_형식이_틀린_조정을_요청하면_403보다_422가_먼저다() throws Exception {
        // given: 금액만 0원, 나머지는 올바름
        String body = """
                {"orderItemId": 5000001, "eventType": "SALE_AMOUNT", "amount": 0, "reason": "금액 오입력 정정"}
                """;

        // when & then
        mockMvc.perform(adjustmentRequest(body)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_7, "BUYER", "SELLER")))
                .andExpect(status().isUnprocessableEntity());                           // 422
    }

    // API: POST /api/v1/payouts/adjustments (정산 수동 조정, 막아 둠)
    // 요청: 관리자 토큰, 금액 0원
    // 확인: 422이고 오류 목록에 amountNotZero가 나오는지 (0원 조정은 장부에 의미 없는 줄만 남겨서 거절)
    @Test
    void 조정_금액이_0원이면_422이다() throws Exception {
        // given: 금액만 0원, 나머지는 올바름
        String body = """
                {"orderItemId": 5000001, "eventType": "SALE_AMOUNT", "amount": 0, "reason": "금액 오입력 정정"}
                """;

        // when & then
        mockMvc.perform(adjustmentRequest(body)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isUnprocessableEntity())                             // 422
                .andExpect(jsonPath("$.errors[*].path").value(contains("amountNotZero")));
    }

    // API: POST /api/v1/payouts/adjustments (정산 수동 조정, 막아 둠)
    // 요청: 관리자 토큰, 조정 사유가 공백뿐 ("   ")
    // 확인: 422이고 오류 목록에 reason이 나오는지 (서버가 앞뒤 공백을 지운 뒤 빈 값이라 필수값 검증에 걸림)
    @Test
    void 조정_사유가_공백뿐이면_422이다() throws Exception {
        // given: 사유만 공백뿐, 나머지는 올바름
        String body = """
                {"orderItemId": 5000001, "eventType": "SALE_AMOUNT", "amount": -2000, "reason": "   "}
                """;

        // when & then
        mockMvc.perform(adjustmentRequest(body)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isUnprocessableEntity())                             // 422
                .andExpect(jsonPath("$.errors[*].path").value(contains("reason")));
    }

    // ---------- 도우미: 요청 ----------

    // 1건 수동 실행 요청 (아직 임시 역할 헤더로 받음. 회원 ID는 쓰지 않는 API)
    private MockHttpServletRequestBuilder runOrderItemRequest(String role) {
        return post("/api/v1/payouts/run/order-items/{orderItemId}", ORDER_ITEM_ID)
                .header(MEMBER_ROLE_HEADER, role);
    }

    // 수동 조정 요청 (본문은 JSON). 토큰은 테스트마다 .header(...)로 붙임 (토큰 없는 요청도 확인하려고 여기서 붙이지 않음)
    private MockHttpServletRequestBuilder adjustmentRequest(String body) {
        return post("/api/v1/payouts/adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
