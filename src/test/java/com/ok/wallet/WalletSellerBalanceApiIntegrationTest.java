package com.ok.wallet;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import com.ok.wallet.service.SellerWalletService;
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

// 토큰으로 판매자 잔액 API를 호출해, 역할 검사(@PreAuthorize)와 응답 값을 실제와 같게 확인한다.
// 잔액은 실제 정산 입금과 같은 경로(depositPayout)로 넣는다.
class WalletSellerBalanceApiIntegrationTest extends AbstractIntegrationTest {
    private static final Long SELLER_ID = 9_401L;
    private static final Long OTHER_SELLER_ID = 9_402L;
    private static final String BALANCE_URL = "/api/v1/wallets/sellers/me/balance";

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    SellerWalletService sellerWalletService;

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
    void 토큰_없이_조회하면_401() throws Exception {
        mockMvc.perform(get(BALANCE_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 판매자가_아닌_회원이_조회하면_403() throws Exception {
        mockMvc.perform(get(BALANCE_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "BUYER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 판매자_지갑이_아직_없으면_잔액_0원을_응답한다() throws Exception {
        mockMvc.perform(get(BALANCE_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void 입금된_정산금만큼의_잔액을_응답한다() throws Exception {
        // Given: 정산 두 건이 입금됨
        sellerWalletService.depositPayout(SELLER_ID, 9_401_001L, 12_700);
        sellerWalletService.depositPayout(SELLER_ID, 9_401_002L, 8_000);

        // When & Then
        mockMvc.perform(get(BALANCE_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(20_700));
    }

    @Test
    void 다른_판매자가_아니라_토큰의_판매자_잔액을_응답한다() throws Exception {
        // Given: 두 판매자의 잔액이 다름
        sellerWalletService.depositPayout(SELLER_ID, 9_401_001L, 12_700);
        sellerWalletService.depositPayout(OTHER_SELLER_ID, 9_402_001L, 5_000);

        // When & Then
        mockMvc.perform(get(BALANCE_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(OTHER_SELLER_ID, "SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(5_000));
    }

    // FK 때문에 원장을 지갑보다 먼저 지운다. 이 클래스의 회원 데이터만 지운다.
    private void cleanUp() {
        for (Long memberId : List.of(SELLER_ID, OTHER_SELLER_ID)) {
            jdbcTemplate.update("""
                    DELETE l FROM seller_wallet_ledger l
                    JOIN seller_wallet w ON l.seller_wallet_id = w.id
                    WHERE w.member_id = ?
                    """, memberId);
            jdbcTemplate.update("DELETE FROM seller_wallet WHERE member_id = ?", memberId);
        }
    }
}
