package com.ok.wallet;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import com.ok.wallet.application.dto.TossCancelResult;
import com.ok.wallet.application.dto.TossPaymentLookupResult;
import com.ok.wallet.application.dto.TossPaymentStatus;
import com.ok.wallet.application.port.out.TossPaymentsClient;
import com.ok.wallet.support.WalletTestDataCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 관리자 충전 처리 API를 호출해, 관리자만 쓸 수 있는지와 요청 검증·상태 코드·오류 type을 실제와 같게 확인한다.
// 토스 상태별 처리 규칙은 ChargeAdminServiceIntegrationTest가 확인하고, 토스 호출만 Mock으로 바꾼다.
class WalletChargeAdminApiIntegrationTest extends AbstractIntegrationTest {
    private static final Long ADMIN_ID = 1L;
    private static final Long MEMBER_ID = 9_101L;
    private static final long AMOUNT = 10_000;
    private static final String PAYMENT_KEY = "admin-api-payment-key";
    private static final String ADMIN_CHARGES_URL = "/api/v1/admin/wallets/charges";

    @MockitoBean
    TossPaymentsClient tossPaymentsClient;

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        new WalletTestDataCleaner(jdbcTemplate).deleteAll();
    }

    @AfterEach
    void tearDown() {
        new WalletTestDataCleaner(jdbcTemplate).verifyAndDeleteAll();
    }

    @Test
    void 토큰_없이_목록을_조회하면_401() throws Exception {
        mockMvc.perform(get(ADMIN_CHARGES_URL))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @MethodSource("관리자가_아닌_역할")
    void 관리자가_아니면_목록을_조회할_수_없다(String role) throws Exception {
        mockMvc.perform(get(ADMIN_CHARGES_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, role)))
                .andExpect(status().isForbidden());
    }

    static Stream<String> 관리자가_아닌_역할() {
        return Stream.of("BUYER", "SELLER");
    }

    // 요청 본문은 검증을 통과하는 값으로 보낸다. 본문이 틀리면 역할 확인 전에 422가 나기 때문
    @Test
    void 구매자_토큰으로_결제를_취소하면_403() throws Exception {
        String chargeNo = heldCharge();

        mockMvc.perform(post(ADMIN_CHARGES_URL + "/" + chargeNo + "/cancel-payment")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "관리자 취소"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void 관리자는_처리할_충전_건_목록을_본다() throws Exception {
        String chargeNo = heldCharge();

        asAdmin(get(ADMIN_CHARGES_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].chargeNo").value(chargeNo))
                .andExpect(jsonPath("$[0].memberId").value(MEMBER_ID))
                .andExpect(jsonPath("$[0].amount").value(AMOUNT))
                .andExpect(jsonPath("$[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[0].heldForReview").value(true))
                .andExpect(jsonPath("$[0].createdAt").value(notNullValue()));
    }

    @Test
    void 관리자는_충전_건과_토스_결제_상태를_함께_본다() throws Exception {
        String chargeNo = heldCharge();
        given(tossPaymentsClient.findPayment(chargeNo))
                .willReturn(new TossPaymentLookupResult.Found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));

        asAdmin(get(ADMIN_CHARGES_URL + "/" + chargeNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.charge.chargeNo").value(chargeNo))
                .andExpect(jsonPath("$.tossLookup").value("FOUND"))
                .andExpect(jsonPath("$.tossStatus").value("DONE"))
                .andExpect(jsonPath("$.tossTotalAmount").value(AMOUNT));
    }

    @Test
    void 없는_충전_번호를_조회하면_404() throws Exception {
        asAdmin(get(ADMIN_CHARGES_URL + "/CHG_none"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("wallet/charge-not-found")));
    }

    @Test
    void 관리자가_토스가_승인한_결제를_취소하면_200과_취소_시각을_받는다() throws Exception {
        String chargeNo = heldCharge();
        given(tossPaymentsClient.findPayment(chargeNo))
                .willReturn(new TossPaymentLookupResult.Found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString()))
                .willReturn(new TossCancelResult.Canceled());

        asAdmin(post(ADMIN_CHARGES_URL + "/" + chargeNo + "/cancel-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "관리자 확인 후 결제 취소"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("PAYMENT_CANCELED"))
                .andExpect(jsonPath("$.paymentCanceledAt").value(notNullValue()));
    }

    @Test
    void 관리자가_토스가_승인한_멈춘_건을_반영하면_200과_완료_상태를_받는다() throws Exception {
        String chargeNo = heldCharge();
        given(tossPaymentsClient.findPayment(chargeNo))
                .willReturn(new TossPaymentLookupResult.Found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));

        asAdmin(post(ADMIN_CHARGES_URL + "/" + chargeNo + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void 토스가_승인한_결제를_실패로_두려_하면_409() throws Exception {
        String chargeNo = heldCharge();
        given(tossPaymentsClient.findPayment(chargeNo))
                .willReturn(new TossPaymentLookupResult.Found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));

        asAdmin(post(ADMIN_CHARGES_URL + "/" + chargeNo + "/fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "승인되지 않음"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("wallet/charge-toss-status-mismatch")));
    }

    @Test
    void 토스_결제_상태를_모르면_503() throws Exception {
        String chargeNo = heldCharge();
        given(tossPaymentsClient.findPayment(chargeNo)).willReturn(new TossPaymentLookupResult.Unknown("토스 응답 없음"));

        asAdmin(post(ADMIN_CHARGES_URL + "/" + chargeNo + "/complete"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type", endsWith("wallet/charge-toss-status-unknown")));
    }

    @ParameterizedTest
    @MethodSource("잘못된_사유")
    void 사유가_없거나_200자를_넘으면_422(String body) throws Exception {
        String chargeNo = heldCharge();

        asAdmin(post(ADMIN_CHARGES_URL + "/" + chargeNo + "/cancel-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    static Stream<String> 잘못된_사유() {
        return Stream.of(
                "{}",
                """
                {"reason": "   "}
                """,
                """
                {"reason": "%s"}
                """.formatted("가".repeat(201)));
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")));
    }

    // 복구가 토스 답만으로 정하지 못해 멈춘 처리 중 건 (다음 확인 시각이 비어 있음)
    private String heldCharge() {
        String chargeNo = "CHG_" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO wallet_charge (charge_no, member_id, amount, status, payment_key, check_count, created_at, updated_at)
                VALUES (?, ?, ?, 'IN_PROGRESS', ?, 10, NOW(6), NOW(6))
                """, chargeNo, MEMBER_ID, AMOUNT, PAYMENT_KEY);
        return chargeNo;
    }
}
