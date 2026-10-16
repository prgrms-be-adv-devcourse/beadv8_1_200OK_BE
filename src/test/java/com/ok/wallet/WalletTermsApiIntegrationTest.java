package com.ok.wallet;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 약관 조회·동의 API를 호출해, 경계 검증(토큰, 요청 본문)부터 저장까지 실제와 같게 확인한다.
class WalletTermsApiIntegrationTest extends AbstractIntegrationTest {
    private static final Long MEMBER_ID = 7_001L;
    private static final String TERMS_URL = "/api/v1/wallets/me/terms";
    private static final String AGREEMENTS_URL = "/api/v1/wallets/me/terms/agreements";

    // 마이그레이션으로 들어간 필수 약관 3종 v1 전체 동의
    private static final String ALL_AGREE_BODY = """
            {"agreements": [
              {"type": "ELECTRONIC_FINANCIAL_TRANSACTION", "version": 1},
              {"type": "PREPAID_PAYMENT_INSTRUMENT", "version": 1},
              {"type": "PERSONAL_INFORMATION_COLLECTION", "version": 1}
            ]}
            """;

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 보안 필터를 적용해야 토큰 검증과 401 처리가 실제와 같게 동작한다
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 토큰_없이_조회하면_401() throws Exception {
        mockMvc.perform(get(TERMS_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 처음_조회하면_현재_버전_약관이_모두_미동의로_나온다() throws Exception {
        getTerms()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms", hasSize(3)))
                .andExpect(jsonPath("$.terms[*].version", everyItem(is(1))))
                .andExpect(jsonPath("$.terms[*].agreed", everyItem(is(false))));
    }

    @Test
    void 필수_약관에_모두_동의하면_204이고_다시_조회하면_모두_동의로_나온다() throws Exception {
        agree(ALL_AGREE_BODY)
                .andExpect(status().isNoContent());

        getTerms()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms[*].agreed", everyItem(is(true))));
    }

    @Test
    void 이미_동의한_뒤_같은_요청을_다시_보내도_204() throws Exception {
        agree(ALL_AGREE_BODY).andExpect(status().isNoContent());

        agree(ALL_AGREE_BODY)
                .andExpect(status().isNoContent());
    }

    @Test
    void 동의할_약관_목록이_비어_있으면_422() throws Exception {
        agree("""
                {"agreements": []}
                """)
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void 필수_약관이_빠지면_422() throws Exception {
        agree("""
                {"agreements": [
                  {"type": "ELECTRONIC_FINANCIAL_TRANSACTION", "version": 1},
                  {"type": "PREPAID_PAYMENT_INSTRUMENT", "version": 1}
                ]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("wallet/required-terms-not-agreed")));
    }

    @Test
    void 알_수_없는_약관_유형이면_422() throws Exception {
        agree("""
                {"agreements": [
                  {"type": "ELECTRONIC_FINANCIAL_TRANSACTION", "version": 1},
                  {"type": "PREPAID_PAYMENT_INSTRUMENT", "version": 1},
                  {"type": "PERSONAL_INFORMATION_COLLECTION", "version": 1},
                  {"type": "UNKNOWN_TERMS", "version": 1}
                ]}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("wallet/invalid-terms-type")));
    }

    @Test
    void 약관이_개정된_뒤_이전_버전으로_동의하면_409() throws Exception {
        // 사용자가 v1 화면을 보는 사이 전자금융거래 약관이 v2로 개정됨
        jdbcTemplate.update("""
                INSERT INTO wallet_terms (type, version, title, content, created_at, updated_at)
                VALUES ('ELECTRONIC_FINANCIAL_TRANSACTION', 2, '개정 약관', '개정 본문', NOW(6), NOW(6))
                """);

        agree(ALL_AGREE_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("wallet/terms-version-outdated")));
    }

    private ResultActions getTerms() throws Exception {
        return mockMvc.perform(get(TERMS_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER")));
    }

    private ResultActions agree(String body) throws Exception {
        return mockMvc.perform(post(AGREEMENTS_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // 이 클래스의 회원 동의와, 테스트에서 추가한 개정 약관(v2 이상)을 지운다. 마이그레이션의 v1은 그대로 둔다.
    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM wallet_terms_agreement WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM wallet_terms WHERE version > 1");
    }
}
