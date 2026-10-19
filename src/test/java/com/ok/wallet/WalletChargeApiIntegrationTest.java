package com.ok.wallet;

import com.jayway.jsonpath.JsonPath;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import com.ok.wallet.client.toss.TossConfirmResult;
import com.ok.wallet.client.toss.TossPaymentsClient;
import com.ok.wallet.domain.TermsType;
import com.ok.wallet.service.TermsService;
import com.ok.wallet.service.dto.TermsAgreeCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 충전 API를 호출해, 경계 검증(토큰, 요청 본문)과 상태 코드·오류 type을 실제와 같게 확인한다.
// 충전 흐름의 세부 규칙은 ChargeServiceTest가 확인하고, 토스 호출만 Mock으로 바꾼다.
class WalletChargeApiIntegrationTest extends AbstractIntegrationTest {
    private static final Long MEMBER_ID = 9_001L;
    private static final Long OTHER_MEMBER_ID = 9_002L;
    private static final String CHARGES_URL = "/api/v1/wallets/me/charges";
    private static final String PAYMENT_KEY = "payment-key-1";

    @MockitoBean
    TossPaymentsClient tossPaymentsClient;

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    TermsService termsService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // 충전 요청

    @Test
    void 토큰_없이_충전을_요청하면_401() throws Exception {
        mockMvc.perform(post(CHARGES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 10000}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 약관에_동의한_회원이_충전을_요청하면_201과_충전_번호를_받는다() throws Exception {
        agreeAllTerms(MEMBER_ID);

        requestCharge(MEMBER_ID, """
                {"amount": 10000}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chargeNo", startsWith("CHG_")))
                .andExpect(jsonPath("$.amount").value(10_000));
    }

    @Test
    void 약관에_동의하지_않고_충전을_요청하면_409() throws Exception {
        requestCharge(MEMBER_ID, """
                {"amount": 10000}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("wallet/terms-agreement-required")));
    }

    @Test
    void 충전_금액이_없으면_422() throws Exception {
        agreeAllTerms(MEMBER_ID);

        requestCharge(MEMBER_ID, "{}")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void 충전_금액이_만원_단위가_아니면_422() throws Exception {
        agreeAllTerms(MEMBER_ID);

        requestCharge(MEMBER_ID, """
                {"amount": 15000}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("wallet/invalid-charge-amount")));
    }

    // 승인

    @Test
    void 충전을_요청하고_승인하면_완료되고_잔액이_는다() throws Exception {
        // Given
        String chargeNo = chargeNoOf(MEMBER_ID, 10_000);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .willReturn(new TossConfirmResult.Approved(PAYMENT_KEY));

        // When & Then
        confirm(MEMBER_ID, chargeNo, PAYMENT_KEY, 10_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeNo").value(chargeNo))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(10_000));
        assertThat(balanceOf(MEMBER_ID)).isEqualTo(10_000);
    }

    @Test
    void 토스가_거절해도_요청은_정상_처리되어_200과_실패_상태를_받는다() throws Exception {
        String chargeNo = chargeNoOf(MEMBER_ID, 10_000);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .willReturn(new TossConfirmResult.Rejected("REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족"));

        confirm(MEMBER_ID, chargeNo, PAYMENT_KEY, 10_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void 결제_금액이_요청과_다르면_422() throws Exception {
        String chargeNo = chargeNoOf(MEMBER_ID, 10_000);

        confirm(MEMBER_ID, chargeNo, PAYMENT_KEY, 20_000)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("wallet/charge-amount-mismatch")));
    }

    @Test
    void 타인의_충전_건을_승인하면_404() throws Exception {
        String chargeNo = chargeNoOf(MEMBER_ID, 10_000);

        confirm(OTHER_MEMBER_ID, chargeNo, PAYMENT_KEY, 10_000)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("wallet/charge-not-found")));
    }

    @Test
    void 결제_키가_없으면_422() throws Exception {
        String chargeNo = chargeNoOf(MEMBER_ID, 10_000);

        mockMvc.perform(post(CHARGES_URL + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chargeNo": "%s", "paymentKey": "", "amount": 10000}
                                """.formatted(chargeNo)))
                .andExpect(status().isUnprocessableContent());
    }

    // 결제 실패

    @Test
    void 결제창에서_실패하면_200과_실패_상태를_받는다() throws Exception {
        String chargeNo = chargeNoOf(MEMBER_ID, 10_000);

        mockMvc.perform(post(CHARGES_URL + "/fail")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chargeNo": "%s", "code": "PAY_PROCESS_CANCELED", "message": "사용자 취소"}
                                """.formatted(chargeNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    private ResultActions requestCharge(Long memberId, String body) throws Exception {
        return mockMvc.perform(post(CHARGES_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions confirm(Long memberId, String chargeNo, String paymentKey, long amount) throws Exception {
        return mockMvc.perform(post(CHARGES_URL + "/confirm")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"chargeNo": "%s", "paymentKey": "%s", "amount": %d}
                        """.formatted(chargeNo, paymentKey, amount)));
    }

    // 약관에 동의하고 충전을 요청해 받은 충전 번호
    private String chargeNoOf(Long memberId, long amount) throws Exception {
        agreeAllTerms(memberId);
        String body = requestCharge(memberId, """
                {"amount": %d}
                """.formatted(amount))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.chargeNo");
    }

    private void agreeAllTerms(Long memberId) {
        List<TermsAgreeCommand> commands = Arrays.stream(TermsType.values())
                .map(type -> new TermsAgreeCommand(type.name(), 1))
                .toList();
        termsService.agree(memberId, commands);
    }

    private long balanceOf(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM buyer_wallet WHERE member_id = ?", Long.class, memberId);
    }

    // FK 때문에 원장을 지갑보다 먼저 지운다. 이 클래스의 회원 데이터만 지운다.
    private void cleanUp() {
        for (Long memberId : List.of(MEMBER_ID, OTHER_MEMBER_ID)) {
            jdbcTemplate.update("""
                    DELETE l FROM buyer_wallet_ledger l
                    JOIN buyer_wallet w ON l.buyer_wallet_id = w.id
                    WHERE w.member_id = ?
                    """, memberId);
            jdbcTemplate.update("DELETE FROM buyer_wallet WHERE member_id = ?", memberId);
            jdbcTemplate.update("DELETE FROM wallet_charge WHERE member_id = ?", memberId);
            jdbcTemplate.update("DELETE FROM wallet_terms_agreement WHERE member_id = ?", memberId);
        }
    }
}
