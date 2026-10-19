package com.ok.wallet;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import com.ok.wallet.service.BuyerWalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 본인 잔액 API를 호출해, 토큰 검증과 응답 값을 실제와 같게 확인한다.
class WalletBalanceApiIntegrationTest extends AbstractIntegrationTest {
    private static final Long MEMBER_ID = 9_101L;
    private static final Long OTHER_MEMBER_ID = 9_102L;
    private static final String BALANCE_URL = "/api/v1/wallets/me/balance";

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    BuyerWalletService buyerWalletService;

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

    @Test
    void 토큰_없이_잔액을_조회하면_401() throws Exception {
        mockMvc.perform(get(BALANCE_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 지갑이_없으면_잔액_0원과_보유_한도를_응답한다() throws Exception {
        mockMvc.perform(get(BALANCE_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.maxBalance").value(500_000));
    }

    @Test
    void 충전한_만큼의_잔액을_응답한다() throws Exception {
        // Given
        buyerWalletService.chargeByPayment(MEMBER_ID, "pay-balance-1", 30_000);

        // When & Then
        mockMvc.perform(get(BALANCE_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(30_000))
                .andExpect(jsonPath("$.maxBalance").value(500_000));
    }

    @Test
    void 다른_회원의_잔액이_아니라_토큰의_회원_잔액을_응답한다() throws Exception {
        // Given: 두 회원의 잔액이 다름
        buyerWalletService.chargeByPayment(MEMBER_ID, "pay-balance-1", 30_000);
        buyerWalletService.chargeByPayment(OTHER_MEMBER_ID, "pay-balance-2", 10_000);

        // When & Then: 토큰의 회원 잔액만 보인다
        mockMvc.perform(get(BALANCE_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(OTHER_MEMBER_ID, "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(10_000));
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
        }
    }
}
