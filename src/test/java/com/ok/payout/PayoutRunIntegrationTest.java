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

// 통합 테스트: 정산 전체 수동 실행(POST /api/v1/payouts/run)이 관리자만 실행되는지 실제 HTTP 요청으로 확인
//
// [확인하는 것]
//   - 관리자 토큰이면 배치가 끝까지 돌고(COMPLETED) 200
//   - 판매자·구매자 토큰이면 403이고 배치가 돌지 않음
//   - 토큰이 없으면 401이고 배치가 돌지 않음
// 관리자 확인은 컨트롤러의 @PreAuthorize("hasRole('ADMIN')")가 하고, 401·403 응답은 공통 처리기(ApiControllerAdvice)가 만듦
//
// [배치가 돌았는지 보는 방법]
// Spring Batch 실행 기록(JobRepository)에서 정산 Job 실행 횟수를 셈
// 실행마다 requestedAt이 달라 새 JobInstance가 생기므로(PayoutJobRunner 주석), 1번 돌면 1 늘어남
//
// [준비]
// 정산할 데이터는 넣지 않음. 정산할 후보가 없어도 배치는 정상 종료(COMPLETED)하고 실행 기록은 남음
// 읽는 방법(mockMvc·jsonPath)은 PayoutQueryIntegrationTest 위의 설명과 같음
class PayoutRunIntegrationTest extends AbstractIntegrationTest {

    private static final String RUN_URL = "/api/v1/payouts/run";
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

    // API: POST /api/v1/payouts/run (정산 전체 수동 실행)
    // 요청: 관리자 토큰
    // 확인: 200이고 배치 상태가 COMPLETED인지, 정산 Job이 1번 실행됐는지
    @Test
    void 관리자가_실행하면_배치가_돌고_200이다() throws Exception {
        // given
        int runCountBefore = payoutJobRunCount();

        // when & then
        mockMvc.perform(post(RUN_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(1L, "ADMIN")))
                .andExpect(status().isOk())                                // 200
                .andExpect(jsonPath("$.status").value("COMPLETED"));       // 배치가 끝까지 성공했는지

        // then: 정산 Job이 1번 실행됐는지
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore + 1);
    }

    // API: POST /api/v1/payouts/run (정산 전체 수동 실행)
    // 요청: 판매자 토큰(BUYER, SELLER)
    // 확인: 403이고 정산 Job이 실행되지 않았는지
    @Test
    void 판매자가_실행하면_403이고_배치가_돌지_않는다() throws Exception {
        // given
        int runCountBefore = payoutJobRunCount();

        // when & then
        mockMvc.perform(post(RUN_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(7L, "BUYER", "SELLER")))
                .andExpect(status().isForbidden())                         // 403
                .andExpect(jsonPath("$.type").value(endsWith(FORBIDDEN_TYPE)));

        // then: 막혔으니 정산 Job 실행 횟수는 그대로
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore);
    }

    // API: POST /api/v1/payouts/run (정산 전체 수동 실행)
    // 요청: 구매자 토큰(BUYER)
    // 확인: 403이고 정산 Job이 실행되지 않았는지
    @Test
    void 구매자가_실행하면_403이고_배치가_돌지_않는다() throws Exception {
        // given
        int runCountBefore = payoutJobRunCount();

        // when & then
        mockMvc.perform(post(RUN_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(3L, "BUYER")))
                .andExpect(status().isForbidden())                         // 403
                .andExpect(jsonPath("$.type").value(endsWith(FORBIDDEN_TYPE)));

        // then: 막혔으니 정산 Job 실행 횟수는 그대로
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore);
    }

    // API: POST /api/v1/payouts/run (정산 전체 수동 실행)
    // 요청: 로그인 토큰 없음
    // 확인: 401이고 정산 Job이 실행되지 않았는지
    @Test
    void 토큰_없이_실행하면_401이고_배치가_돌지_않는다() throws Exception {
        // given
        int runCountBefore = payoutJobRunCount();

        // when & then
        mockMvc.perform(post(RUN_URL))
                .andExpect(status().isUnauthorized());                     // 401

        // then: 막혔으니 정산 Job 실행 횟수는 그대로
        assertThat(payoutJobRunCount()).isEqualTo(runCountBefore);
    }

    // 정산 Job이 지금까지 실행된 횟수
    // getJobInstanceCount()는 한 번도 실행된 적이 없으면 0이 아니라 예외(NoSuchJobException)를 던져서,
    // 빈 목록을 돌려주는 findJobInstances(...).size()를 씀 (테스트 DB가 비어 있을 때도 안전)
    private int payoutJobRunCount() {
        return jobRepository.findJobInstances(PAYOUT_JOB_NAME).size();
    }
}
