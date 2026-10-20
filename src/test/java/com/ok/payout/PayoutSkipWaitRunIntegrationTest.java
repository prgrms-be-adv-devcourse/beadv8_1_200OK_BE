package com.ok.payout;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 통합 테스트: 정산 대기 건너뛰기 실행(POST /api/v1/payouts/run/skip-wait)이 관리자만 실행되는지 실제 HTTP 요청으로 확인 (#224)
//
// [확인하는 것]
//   - 관리자 토큰이면 배치가 끝까지 돌고(COMPLETED) 200
//   - 판매자·구매자 토큰이면 403이고 배치가 돌지 않음
//   - 토큰이 없으면 401이고 배치가 돌지 않음
// 15일이 안 지난 후보도 정산되는지는 PayoutSettleIntegrationTest에서 확인하므로 여기서는 권한과 API 연결만 봄
// 임시 기능이라 기능을 지울 때 이 클래스도 함께 지울 수 있도록 PayoutRunIntegrationTest와 분리함
//
// [배치가 돌았는지 보는 방법·준비]
// PayoutRunIntegrationTest 위의 설명과 같음 (정산 Job 실행 횟수를 세고, 정산할 데이터는 넣지 않음)
class PayoutSkipWaitRunIntegrationTest extends AbstractIntegrationTest {

    private static final String SKIP_WAIT_RUN_URL = "/api/v1/payouts/run/skip-wait";
    // PayoutJobConfig에서 만든 정산 Job 이름
    private static final String PAYOUT_JOB_NAME = "payoutJob";
    // @PreAuthorize가 거절하면 공통 처리기가 CommonErrorCode.FORBIDDEN으로 응답 (type 앞부분은 설정마다 달라 끝부분만 비교)
    private static final String FORBIDDEN_TYPE = "/forbidden";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    // 요청에 붙일 로그인 토큰을 만듦 (실제 토큰과 같은 형식이라 보안 필터가 똑같이 검증함)
    @Autowired
    private TestAccessTokens tokens;

    @Autowired
    private JobRepository jobRepository;

    // 보안 필터를 적용해야 토큰 검증과 401·403 처리가 실제와 같게 동작한다 (DEVELOPMENT.md "테스트에서 토큰 보내기")
    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // API: POST /api/v1/payouts/run/skip-wait (정산 대기 건너뛰기 실행)
    // 요청: 관리자 토큰
    // 확인: 200이고 배치 상태가 COMPLETED인지, 정산 Job이 1번 실행됐는지
    @Test
    void 관리자가_실행하면_배치가_돌고_200이다() throws Exception {
        // given
        int runCountBefore = payoutJobRunCount();

        // when & then
        mockMvc.perform(post(SKIP_WAIT_RUN_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(1L, "ADMIN")))
                .andExpect(status().isOk())                                // 200
                .andExpect(jsonPath("$.status").value("COMPLETED"));       // 배치가 끝까지 성공했는지

        // then: 정산 Job이 1번 실행됐는지
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore + 1);
    }

    // API: POST /api/v1/payouts/run/skip-wait (정산 대기 건너뛰기 실행)
    // 요청: 판매자 토큰(BUYER, SELLER)
    // 확인: 403이고 정산 Job이 실행되지 않았는지
    @Test
    void 판매자가_실행하면_403이고_배치가_돌지_않는다() throws Exception {
        // given
        int runCountBefore = payoutJobRunCount();

        // when & then
        mockMvc.perform(post(SKIP_WAIT_RUN_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(7L, "BUYER", "SELLER")))
                .andExpect(status().isForbidden())                         // 403
                .andExpect(jsonPath("$.type").value(endsWith(FORBIDDEN_TYPE)));

        // then: 막혔으니 정산 Job 실행 횟수는 그대로
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore);
    }

    // API: POST /api/v1/payouts/run/skip-wait (정산 대기 건너뛰기 실행)
    // 요청: 구매자 토큰(BUYER)
    // 확인: 403이고 정산 Job이 실행되지 않았는지
    @Test
    void 구매자가_실행하면_403이고_배치가_돌지_않는다() throws Exception {
        // given
        int runCountBefore = payoutJobRunCount();

        // when & then
        mockMvc.perform(post(SKIP_WAIT_RUN_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(3L, "BUYER")))
                .andExpect(status().isForbidden())                         // 403
                .andExpect(jsonPath("$.type").value(endsWith(FORBIDDEN_TYPE)));

        // then: 막혔으니 정산 Job 실행 횟수는 그대로
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore);
    }

    // API: POST /api/v1/payouts/run/skip-wait (정산 대기 건너뛰기 실행)
    // 요청: 로그인 토큰 없음
    // 확인: 401이고 정산 Job이 실행되지 않았는지
    @Test
    void 토큰_없이_실행하면_401이고_배치가_돌지_않는다() throws Exception {
        // given
        int runCountBefore = payoutJobRunCount();

        // when & then
        mockMvc.perform(post(SKIP_WAIT_RUN_URL))
                .andExpect(status().isUnauthorized());                     // 401

        // then: 막혔으니 정산 Job 실행 횟수는 그대로
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore);
    }

    // 정산 Job이 지금까지 실행된 횟수 (PayoutRunIntegrationTest.payoutJobRunCount와 같은 이유로 findJobInstances 사용)
    private int payoutJobRunCount() {
        return jobRepository.findJobInstances(PAYOUT_JOB_NAME).size();
    }
}
