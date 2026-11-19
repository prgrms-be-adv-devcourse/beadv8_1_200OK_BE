package com.ok.wallet;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import com.ok.wallet.adapter.out.persistence.BuyerWalletJpaRepository;
import com.ok.wallet.domain.BuyerWallet;
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
    private static final Long REFUND_ID = 9_301_001L;
    private static final String LEDGERS_URL = "/api/v1/wallets/me/ledgers";

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    BuyerWalletJpaRepository buyerWalletRepository;

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
    void 관리자_토큰으로_조회하면_403() throws Exception {
        mockMvc.perform(get(LEDGERS_URL).header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "ADMIN")))
                .andExpect(status().isForbidden());
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
    void 환불_반환과_되돌리기_내역도_전체_조회에_반환_처리_기록의_주문_ID와_함께_나온다() throws Exception {
        // Given: 결제 → 두 환불 반환 → 그중 하나를 되돌림. 반환 처리 기록이 환불 ID와 주문 ID를 잇는다
        Long walletId = createWallet(MEMBER_ID);
        insertLedger(walletId, "PAYMENT", 10_000, 0, "PAYMENT:1001", at(10, 12));
        insertRefundRecord(REFUND_ID, 1001L, 3_000);
        insertRefundRecord(REFUND_ID + 1, 1001L, 2_000);
        insertLedger(walletId, "REFUND", 3_000, 3_000, "REFUND:" + REFUND_ID, at(11, 12));
        insertLedger(walletId, "REFUND", 2_000, 5_000, "REFUND:" + (REFUND_ID + 1), at(12, 12));
        insertLedger(walletId, "REFUND_REVERT", 3_000, 2_000, "REFUND_REVERT:" + REFUND_ID, at(13, 12));

        // When & Then
        mockMvc.perform(get(LEDGERS_URL).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].type").value("REFUND_REVERT"))
                .andExpect(jsonPath("$.content[0].orderId").value(1001))
                .andExpect(jsonPath("$.content[0].amount").value(3_000))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(2_000))
                .andExpect(jsonPath("$.content[1].type").value("REFUND"))
                .andExpect(jsonPath("$.content[1].orderId").value(1001))
                .andExpect(jsonPath("$.content[1].amount").value(2_000))
                .andExpect(jsonPath("$.content[2].type").value("REFUND"))
                .andExpect(jsonPath("$.content[2].orderId").value(1001))
                .andExpect(jsonPath("$.content[3].type").value("PAYMENT"))
                .andExpect(jsonPath("$.content[3].orderId").value(1001));
    }

    @Test
    void 결제_환불_반환_되돌리기_내역에_결제_처리_기록의_주문_요약이_함께_나온다() throws Exception {
        // Given: 주문 1001을 결제하고 일부 환불했다가 되돌림. 요약은 결제 처리 기록에만 있다
        Long walletId = createWallet(MEMBER_ID);
        insertPaymentRecord(1001L, 10_000, "사과 외 3건");
        insertRefundRecord(REFUND_ID, 1001L, 3_000);
        insertLedger(walletId, "CHARGE", 10_000, 10_000, "CHARGE:pay-1", at(9, 12));
        insertLedger(walletId, "PAYMENT", 10_000, 0, "PAYMENT:1001", at(10, 12));
        insertLedger(walletId, "REFUND", 3_000, 3_000, "REFUND:" + REFUND_ID, at(11, 12));
        insertLedger(walletId, "REFUND_REVERT", 3_000, 0, "REFUND_REVERT:" + REFUND_ID, at(12, 12));

        // When & Then
        mockMvc.perform(get(LEDGERS_URL).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].type").value("REFUND_REVERT"))
                .andExpect(jsonPath("$.content[0].orderSummary").value("사과 외 3건"))
                .andExpect(jsonPath("$.content[1].type").value("REFUND"))
                .andExpect(jsonPath("$.content[1].orderSummary").value("사과 외 3건"))
                .andExpect(jsonPath("$.content[2].type").value("PAYMENT"))
                .andExpect(jsonPath("$.content[2].orderSummary").value("사과 외 3건"))
                .andExpect(jsonPath("$.content[3].type").value("CHARGE"))
                .andExpect(jsonPath("$.content[3].orderSummary").value(nullValue()));
    }

    @Test
    void 요약을_저장하기_전_결제는_주문_ID는_나오고_주문_요약만_비어서_나온다() throws Exception {
        // Given: 요약 칸이 생기기 전 결제(요약 없음)와 처리 기록조차 없는 결제
        Long walletId = createWallet(MEMBER_ID);
        insertPaymentRecord(1001L, 3_000, null);
        insertLedger(walletId, "PAYMENT", 3_000, 7_000, "PAYMENT:1001", at(10, 12));
        insertLedger(walletId, "PAYMENT", 2_000, 5_000, "PAYMENT:1002", at(11, 12));

        // When & Then
        mockMvc.perform(get(LEDGERS_URL).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].orderId").value(1002))
                .andExpect(jsonPath("$.content[0].orderSummary").value(nullValue()))
                .andExpect(jsonPath("$.content[1].orderId").value(1001))
                .andExpect(jsonPath("$.content[1].orderSummary").value(nullValue()));
    }

    @Test
    void 다른_구매자가_결제한_주문이면_주문_요약을_붙이지_않는다() throws Exception {
        // Given: 반환 기록은 내 것인데 결제 기록의 구매자가 다르다. 반환 규칙상 생기지 않지만 데이터가 어긋난 경우를 가정한다
        Long walletId = createWallet(MEMBER_ID);
        insertPaymentRecord(1001L, OTHER_MEMBER_ID, 10_000, "남의 상품 외 1건");
        insertRefundRecord(REFUND_ID, 1001L, 3_000);
        insertLedger(walletId, "REFUND", 3_000, 3_000, "REFUND:" + REFUND_ID, at(11, 12));

        // When & Then
        mockMvc.perform(get(LEDGERS_URL).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(1001))
                .andExpect(jsonPath("$.content[0].orderSummary").value(nullValue()));
    }

    @Test
    void 환불_반환_유형으로_조회하면_반환_내역만_응답한다() throws Exception {
        // Given
        Long walletId = createWallet(MEMBER_ID);
        insertRefundRecord(REFUND_ID, 1001L, 3_000);
        insertLedger(walletId, "PAYMENT", 10_000, 0, "PAYMENT:1001", at(10, 12));
        insertLedger(walletId, "REFUND", 3_000, 3_000, "REFUND:" + REFUND_ID, at(11, 12));
        insertLedger(walletId, "REFUND_REVERT", 3_000, 0, "REFUND_REVERT:" + REFUND_ID, at(12, 12));

        // When & Then
        mockMvc.perform(get(LEDGERS_URL).param("type", "REFUND")
                        .header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("REFUND"))
                .andExpect(jsonPath("$.content[0].orderId").value(1001));
    }

    @Test
    void 환불_되돌리기_유형으로_조회하면_되돌리기_내역만_응답한다() throws Exception {
        // Given
        Long walletId = createWallet(MEMBER_ID);
        insertRefundRecord(REFUND_ID, 1001L, 3_000);
        insertLedger(walletId, "REFUND", 3_000, 3_000, "REFUND:" + REFUND_ID, at(11, 12));
        insertLedger(walletId, "REFUND_REVERT", 3_000, 0, "REFUND_REVERT:" + REFUND_ID, at(12, 12));

        // When & Then
        mockMvc.perform(get(LEDGERS_URL).param("type", "REFUND_REVERT")
                        .header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("REFUND_REVERT"))
                .andExpect(jsonPath("$.content[0].orderId").value(1001));
    }

    @Test
    void 반환_처리_기록이_없거나_근거_키가_틀린_환불_내역은_주문_ID만_비우고_목록은_응답한다() throws Exception {
        // Given: 기록 없는 환불, 키 형식이 틀린 환불, 기록이 있는 환불
        Long walletId = createWallet(MEMBER_ID);
        insertRefundRecord(REFUND_ID + 2, 1002L, 1_000);
        insertLedger(walletId, "REFUND", 3_000, 3_000, "REFUND:" + REFUND_ID, at(11, 12));
        insertLedger(walletId, "REFUND", 1_000, 4_000, "REFUND:abc", at(12, 12));
        insertLedger(walletId, "REFUND", 1_000, 5_000, "REFUND:" + (REFUND_ID + 2), at(13, 12));

        // When & Then
        mockMvc.perform(get(LEDGERS_URL).header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].orderId").value(1002))
                .andExpect(jsonPath("$.content[1].orderId").value(nullValue()))
                .andExpect(jsonPath("$.content[2].orderId").value(nullValue()));
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

    private void insertRefundRecord(Long refundId, Long orderId, long amount) {
        LocalDateTime now = at(11, 12);
        jdbcTemplate.update("""
                INSERT INTO wallet_order_refund
                    (refund_id, order_id, buyer_id, amount, status, failure_reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'COMPLETED', NULL, ?, ?)
                """, refundId, orderId, MEMBER_ID, amount, now, now);
    }

    private void insertPaymentRecord(Long orderId, long amount, String orderSummary) {
        insertPaymentRecord(orderId, MEMBER_ID, amount, orderSummary);
    }

    private void insertPaymentRecord(Long orderId, Long buyerId, long amount, String orderSummary) {
        LocalDateTime now = at(10, 12);
        jdbcTemplate.update("""
                INSERT INTO wallet_order_payment
                    (order_id, buyer_id, amount, order_summary, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PAID', ?, ?)
                """, orderId, buyerId, amount, orderSummary, now, now);
    }

    // FK 때문에 원장을 지갑보다 먼저 지운다. 이 클래스의 회원 데이터만 지운다.
    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM wallet_order_refund WHERE buyer_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM wallet_order_payment WHERE buyer_id IN (?, ?)", MEMBER_ID, OTHER_MEMBER_ID);
        for (Long memberId : List.of(MEMBER_ID, OTHER_MEMBER_ID)) {
            jdbcTemplate.update("""
                    DELETE l FROM buyer_wallet_ledger l
                    JOIN buyer_wallet w ON l.buyer_wallet_id = w.id
                    WHERE w.member_id = ?
                    """, memberId);
            jdbcTemplate.update("DELETE FROM buyer_wallet WHERE member_id = ?", memberId);
        }
    }

    @Test
    void 날짜_형식이_틀리면_422() throws Exception {
        mockMvc.perform(get(LEDGERS_URL).param("from", "abc")
                        .header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void 페이지_번호가_숫자가_아니면_422() throws Exception {
        mockMvc.perform(get(LEDGERS_URL).param("page", "abc")
                        .header(HttpHeaders.AUTHORIZATION, bearer(MEMBER_ID)))
                .andExpect(status().isUnprocessableContent());
    }
}
