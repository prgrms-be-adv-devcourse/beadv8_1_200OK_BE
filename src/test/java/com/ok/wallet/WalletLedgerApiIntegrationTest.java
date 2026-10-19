package com.ok.wallet;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.repository.BuyerWalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 이용 내역 API를 호출해 조건·검증·응답 형식을 실제와 같게 확인한다.
// 기간을 확인하려고 원장은 생성 시각을 정해 SQL로 넣는다.
class WalletLedgerApiIntegrationTest extends AbstractIntegrationTest {
    private static final Long MEMBER_ID = 9_301L;
    private static final Long OTHER_MEMBER_ID = 9_302L;
    private static final String LEDGERS_URL = "/api/v1/wallets/me/ledgers";

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    BuyerWalletRepository buyerWalletRepository;

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
        mockMvc.perform(get(LEDGERS_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 지갑이_없으면_빈_목록을_응답한다() throws Exception {
        mockMvc.perform(get(LEDGERS_URL).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void 본인_내역을_최신순으로_응답하고_결제_내역에만_주문_ID가_있다() throws Exception {
        // Given: 내 지갑에 충전 → 결제, 다른 회원 지갑에도 충전
        Long walletId = createWallet(MEMBER_ID);
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1", at(10, 12));
        insertLedger(walletId, "PAYMENT", 3_000, 7_000, "PAYMENT:1001", at(15, 12));
        insertLedger(createWallet(OTHER_MEMBER_ID), "CHARGE", 50_000, 50_000, "CHARGE:pay-other", at(16, 12));

        // When & Then
        mockMvc.perform(get(LEDGERS_URL).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].type").value("PAYMENT"))
                .andExpect(jsonPath("$.content[0].orderId").value(1001))
                .andExpect(jsonPath("$.content[0].amount").value(3_000))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(7_000))
                .andExpect(jsonPath("$.content[1].type").value("CHARGE"))
                .andExpect(jsonPath("$.content[1].orderId").value(nullValue()))
                .andExpect(jsonPath("$.content[1].balanceAfter").value(10_000));
    }

    @Test
    void 유형을_주면_그_유형의_내역만_응답한다() throws Exception {
        // Given
        Long walletId = createWallet(MEMBER_ID);
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1", at(10, 12));
        insertLedger(walletId, "PAYMENT", 3_000, 7_000, "PAYMENT:1001", at(15, 12));

        // When & Then
        mockMvc.perform(get(LEDGERS_URL).param("type", "CHARGE")
                        .header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("CHARGE"));
    }

    @Test
    void 기간은_시작일과_종료일_하루_전체를_포함한다() throws Exception {
        // Given: 10/14 23:59, 10/15 00:00, 10/20 23:59, 10/21 00:00
        Long walletId = createWallet(MEMBER_ID);
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1", at(14, 23, 59));
        insertLedger(walletId, "CHARGE", 10_000, 20_000, "CHARGE:pay-2", at(15, 0, 0));
        insertLedger(walletId, "CHARGE", 10_000, 30_000, "CHARGE:pay-3", at(20, 23, 59));
        insertLedger(walletId, "CHARGE", 10_000, 40_000, "CHARGE:pay-4", at(21, 0, 0));

        // When & Then: 10/15 ~ 10/20 조회 시 가운데 두 건만
        mockMvc.perform(get(LEDGERS_URL)
                        .param("from", "2026-10-15").param("to", "2026-10-20")
                        .header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(30_000))
                .andExpect(jsonPath("$.content[1].balanceAfter").value(20_000));
    }

    @Test
    void 페이지_크기만큼_나눠_응답하고_페이지_정보를_함께_준다() throws Exception {
        // Given
        Long walletId = createWallet(MEMBER_ID);
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1", at(10, 12));
        insertLedger(walletId, "CHARGE", 10_000, 20_000, "CHARGE:pay-2", at(11, 12));
        insertLedger(walletId, "CHARGE", 10_000, 30_000, "CHARGE:pay-3", at(12, 12));

        // When & Then
        mockMvc.perform(get(LEDGERS_URL).param("page", "1").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void 시작일이_종료일보다_늦으면_422() throws Exception {
        mockMvc.perform(get(LEDGERS_URL)
                        .param("from", "2026-10-20").param("to", "2026-10-15")
                        .header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void 알_수_없는_유형이면_422() throws Exception {
        mockMvc.perform(get(LEDGERS_URL).param("type", "UNKNOWN")
                        .header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void 페이지_크기가_범위를_벗어나면_422() throws Exception {
        mockMvc.perform(get(LEDGERS_URL).param("size", "51")
                        .header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void 환불_내역은_전체_조회에_나오지_않는다() throws Exception {
        // Given: 환불은 세미 범위 밖이라 이용 내역에 보여 주지 않는다
        Long walletId = createWallet(MEMBER_ID);
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1", at(10, 12));
        insertLedger(walletId, "REFUND", 3_000, 13_000, "REFUND:1", at(11, 12));

        // When & Then
        mockMvc.perform(get(LEDGERS_URL).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("CHARGE"));
    }

    @Test
    void 보여_주지_않는_환불_유형으로_조회하면_422() throws Exception {
        mockMvc.perform(get(LEDGERS_URL).param("type", "REFUND")
                        .header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void 유형이_빈_값이면_전체를_조회한다() throws Exception {
        // Given
        Long walletId = createWallet(MEMBER_ID);
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1", at(10, 12));
        insertLedger(walletId, "PAYMENT", 3_000, 7_000, "PAYMENT:1001", at(15, 12));

        // When & Then
        mockMvc.perform(get(LEDGERS_URL).param("type", "")
                        .header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    private String bearer(Long memberId) {
        return tokens.bearer(memberId, "BUYER");
    }

    private Long createWallet(Long memberId) {
        return buyerWalletRepository.save(BuyerWallet.create(memberId)).getId();
    }

    private static LocalDateTime at(int day, int hour) {
        return LocalDateTime.of(2026, 10, day, hour, 0);
    }

    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, day, hour, minute);
    }

    private void insertLedger(Long buyerWalletId, String type, long amount, long balanceAfter,
                              String referenceKey, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO buyer_wallet_ledger
                    (buyer_wallet_id, type, amount, balance_after, reference_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, buyerWalletId, type, amount, balanceAfter, referenceKey, createdAt, createdAt);
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
