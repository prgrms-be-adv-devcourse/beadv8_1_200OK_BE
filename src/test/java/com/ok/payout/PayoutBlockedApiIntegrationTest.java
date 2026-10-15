package com.ok.payout;

import com.ok.payout.repository.PayoutCandidateItemRepository;
import com.ok.payout.repository.PayoutRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
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
// 403(관리자 전용)과 422(형식 검증) 테스트는 그대로 쓸 수 있다.
//
// [확인하는 것]
//   - 관리자가 부르면 501(아직 지원하지 않는 기능)이고, 데이터가 바뀌지 않음
//   - 관리자가 아니면 403(권한 없음)
//   - 조정 요청의 형식이 틀리면 422 (권한·501 확인보다 먼저 거절됨)
//
// [준비]
// 정산 데이터를 미리 만들 필요가 없어 배치를 돌리지 않음 (조회 API 테스트는 PayoutQueryIntegrationTest)
// 읽는 방법(mockMvc·jsonPath)은 PayoutQueryIntegrationTest 위의 설명과 같음
//
// [요청 본문(JSON)]
// POST 요청은 값을 JSON 본문으로 보내므로 텍스트 블록(""" ... """)에 실제로 보낼 JSON을 그대로 적음
// (객체로 만들면 생성자가 공백을 지워 버려서, "공백뿐인 사유" 같은 잘못된 입력을 그대로 보낼 수 없음)
@AutoConfigureMockMvc
class PayoutBlockedApiIntegrationTest extends AbstractIntegrationTest {

    private static final String MEMBER_ID_HEADER = "X-Member-Id";
    private static final String MEMBER_ROLE_HEADER = "X-Member-Role";
    private static final long ADMIN_ID = 1L;
    private static final long SELLER_7 = 7L;

    // 요청에 쓰는 주문 항목 번호. 다른 테스트의 번호대(6_000_000 ~ 9_000_000대)와 겹치지 않게 정함
    // 주의: 아래 JSON 본문에도 5000001을 그대로 적었으므로, 이 값을 바꾸면 JSON 안의 번호도 함께 바꿀 것
    private static final long ORDER_ITEM_ID = 5_000_001L;

    // 오류 응답의 type 끝부분 (모듈 이름 + 에러 코드 이름을 소문자·하이픈으로 바꾼 값)
    // type 앞부분(주소)은 설정(PROBLEM_BASE_URI)마다 달라서 끝부분만 비교
    private static final String FEATURE_NOT_SUPPORTED_TYPE = "/payout/feature-not-supported";
    private static final String ACCESS_DENIED_TYPE = "/payout/payout-access-denied";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PayoutRepository payoutRepository;

    @Autowired
    private PayoutCandidateItemRepository candidateRepository;

    // ---------- 1건 수동 실행 ----------

    // API: POST /api/v1/payouts/run/order-items/{orderItemId} (정산 1건 수동 실행, 막아 둠)
    // 요청: 관리자, 주문 항목 5000001
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
    // 요청: 판매자, 주문 항목 5000001
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
    // 요청: 관리자, 형식이 올바른 조정 요청 (주문 항목 5000001에서 2,000원 차감)
    // 확인: 501(아직 지원하지 않는 기능)이고, 그 주문 항목에 정산후보(조정 건)가 생기지 않았는지
    @Test
    void 관리자가_수동_조정을_요청하면_501이고_정산후보가_생기지_않는다() throws Exception {
        // given: 형식이 올바른 조정 요청
        String body = """
                {"orderItemId": 5000001, "eventType": "SALE_AMOUNT", "amount": -2000, "reason": "금액 오입력 정정"}
                """;

        // when & then
        mockMvc.perform(adjustmentRequest(ADMIN_ID, "ADMIN", body))
                .andExpect(status().isNotImplemented())                                  // 501
                .andExpect(jsonPath("$.type").value(endsWith(FEATURE_NOT_SUPPORTED_TYPE)));

        // then: 막아 둔 API라 조정 데이터(정산후보)가 만들어지면 안 됨
        assertThat(candidateRepository.findAllByOrderItemId(ORDER_ITEM_ID)).isEmpty();
    }

    // API: POST /api/v1/payouts/adjustments (정산 수동 조정, 막아 둠)
    // 요청: 판매자, 형식이 올바른 조정 요청
    // 확인: 403(권한 없음)인지 (관리자 전용 기능)
    @Test
    void 판매자가_수동_조정을_요청하면_403이다() throws Exception {
        // given: 형식은 올바름 (형식 오류 422가 아니라 권한 때문에 거절되는지 보려고)
        String body = """
                {"orderItemId": 5000001, "eventType": "SALE_AMOUNT", "amount": -2000, "reason": "금액 오입력 정정"}
                """;

        // when & then
        mockMvc.perform(adjustmentRequest(SELLER_7, "SELLER", body))
                .andExpect(status().isForbidden())                                       // 403
                .andExpect(jsonPath("$.type").value(endsWith(ACCESS_DENIED_TYPE)));
    }

    // API: POST /api/v1/payouts/adjustments (정산 수동 조정, 막아 둠)
    // 요청: 관리자, 금액 0원
    // 확인: 422이고 오류 목록에 amountNotZero가 나오는지 (0원 조정은 장부에 의미 없는 줄만 남겨서 거절)
    @Test
    void 조정_금액이_0원이면_422이다() throws Exception {
        // given: 금액만 0원, 나머지는 올바름
        String body = """
                {"orderItemId": 5000001, "eventType": "SALE_AMOUNT", "amount": 0, "reason": "금액 오입력 정정"}
                """;

        // when & then
        mockMvc.perform(adjustmentRequest(ADMIN_ID, "ADMIN", body))
                .andExpect(status().isUnprocessableEntity())                             // 422
                .andExpect(jsonPath("$.errors[*].path").value(contains("amountNotZero")));
    }

    // API: POST /api/v1/payouts/adjustments (정산 수동 조정, 막아 둠)
    // 요청: 관리자, 조정 사유가 공백뿐 ("   ")
    // 확인: 422이고 오류 목록에 reason이 나오는지 (서버가 앞뒤 공백을 지운 뒤 빈 값이라 필수값 검증에 걸림)
    @Test
    void 조정_사유가_공백뿐이면_422이다() throws Exception {
        // given: 사유만 공백뿐, 나머지는 올바름
        String body = """
                {"orderItemId": 5000001, "eventType": "SALE_AMOUNT", "amount": -2000, "reason": "   "}
                """;

        // when & then
        mockMvc.perform(adjustmentRequest(ADMIN_ID, "ADMIN", body))
                .andExpect(status().isUnprocessableEntity())                             // 422
                .andExpect(jsonPath("$.errors[*].path").value(contains("reason")));
    }

    // ---------- 도우미: 요청 ----------

    // 1건 수동 실행 요청 (역할 헤더만 필요. 회원 ID는 쓰지 않는 API)
    private MockHttpServletRequestBuilder runOrderItemRequest(String role) {
        return post("/api/v1/payouts/run/order-items/{orderItemId}", ORDER_ITEM_ID)
                .header(MEMBER_ROLE_HEADER, role);
    }

    // 수동 조정 요청 (회원 ID는 조정한 관리자로 기록될 값, 본문은 JSON)
    private MockHttpServletRequestBuilder adjustmentRequest(long memberId, String role, String body) {
        return post("/api/v1/payouts/adjustments")
                .header(MEMBER_ID_HEADER, memberId)
                .header(MEMBER_ROLE_HEADER, role)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
