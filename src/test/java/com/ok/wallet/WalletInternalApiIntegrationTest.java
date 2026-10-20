package com.ok.wallet;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.service.BuyerWalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 주문 모듈이 호출하는 내부 잔액 API를 확인한다. MockMvc 요청은 기본으로 127.0.0.1에서 오므로 loopback 검사(#204)를 통과한다.
// 응답 필드 이름은 주문 WalletApiClient가 받는 형식(memberId, balance)과 같아야 한다.
class WalletInternalApiIntegrationTest extends AbstractIntegrationTest {
    private static final Long MEMBER_ID = 9_201L;
    private static final Long OTHER_MEMBER_ID = 9_202L;
    private static final String BALANCE_URL = "/internal/v1/wallets/buyers/{memberId}/balance";

    @Autowired
    WebApplicationContext context;

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
    void 지갑이_없으면_회원_ID와_잔액_0원을_응답한다() throws Exception {
        mockMvc.perform(get(BALANCE_URL, MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(MEMBER_ID))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void 충전한_만큼의_잔액을_응답한다() throws Exception {
        // Given
        buyerWalletService.chargeByPayment(MEMBER_ID, "pay-internal-1", 30_000);

        // When & Then
        mockMvc.perform(get(BALANCE_URL, MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(MEMBER_ID))
                .andExpect(jsonPath("$.balance").value(30_000));
    }

    @Test
    void 요청한_회원의_잔액만_응답한다() throws Exception {
        // Given: 두 회원의 잔액이 다름
        buyerWalletService.chargeByPayment(MEMBER_ID, "pay-internal-1", 30_000);
        buyerWalletService.chargeByPayment(OTHER_MEMBER_ID, "pay-internal-2", 10_000);

        // When & Then
        mockMvc.perform(get(BALANCE_URL, OTHER_MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(OTHER_MEMBER_ID))
                .andExpect(jsonPath("$.balance").value(10_000));
    }

    @Test
    void 같은_컨테이너가_아닌_곳에서_토큰_없이_부르면_401로_거부된다() throws Exception {
        // 인증 없이 회원 ID만으로 잔액이 보이는 API라, loopback 검사가 빠지면 운영에서 다른 회원 잔액이 열린다.
        // nginx를 거친 요청처럼 docker 네트워크 주소에서 온 요청을 흉내 낸다
        mockMvc.perform(get(BALANCE_URL, MEMBER_ID)
                        .with(request -> {
                            request.setRemoteAddr("172.18.0.5");
                            return request;
                        }))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.balance").doesNotExist());
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
