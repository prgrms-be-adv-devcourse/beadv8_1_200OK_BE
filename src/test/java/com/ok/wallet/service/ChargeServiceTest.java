package com.ok.wallet.service;

import com.ok.common.exception.RestApiException;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.client.toss.TossConfirmResult;
import com.ok.wallet.client.toss.TossPaymentsClient;
import com.ok.wallet.domain.TermsType;
import com.ok.wallet.exception.WalletErrorCode;
import com.ok.wallet.service.dto.ChargeConfirmResult;
import com.ok.wallet.service.dto.ChargeRequestResult;
import com.ok.wallet.service.dto.TermsAgreeCommand;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

// 충전 요청 → 승인 → 실패 흐름을 실제 DB로 확인하고, 토스 호출만 Mock으로 바꾼다.
// 트랜잭션 두 개와 지갑 잠금이 실제로 커밋되어야 하므로 테스트에 @Transactional을 붙이지 않는다.
class ChargeServiceTest extends AbstractIntegrationTest {
    private static final Long MEMBER_ID = 8_001L;
    private static final Long OTHER_MEMBER_ID = 8_002L;
    private static final String PAYMENT_KEY = "payment-key-1";

    @MockitoBean
    TossPaymentsClient tossPaymentsClient;

    @Autowired
    ChargeService chargeService;

    @Autowired
    ChargeConfirmService chargeConfirmService;

    @Autowired
    TermsService termsService;

    @Autowired
    BuyerWalletService buyerWalletService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // 충전 요청

    @Test
    void 약관에_동의한_회원이_충전을_요청하면_대기_상태로_저장된다() {
        // Given
        agreeAllTerms(MEMBER_ID);

        // When
        ChargeRequestResult result = chargeService.request(MEMBER_ID, 10_000);

        // Then
        assertThat(result.chargeNo()).startsWith("CHG_");
        assertThat(result.amount()).isEqualTo(10_000);
        assertThat(statusOf(result.chargeNo())).isEqualTo("PENDING");
    }

    @Test
    void 약관에_동의하지_않으면_충전을_요청할_수_없다() {
        assertErrorCode(() -> chargeService.request(MEMBER_ID, 10_000), WalletErrorCode.TERMS_AGREEMENT_REQUIRED);
        assertThat(countCharges(MEMBER_ID)).isZero();
    }

    @Test
    void 금액_규칙에_맞지_않으면_충전을_요청할_수_없다() {
        agreeAllTerms(MEMBER_ID);

        assertErrorCode(() -> chargeService.request(MEMBER_ID, 15_000), WalletErrorCode.INVALID_CHARGE_AMOUNT);
        assertThat(countCharges(MEMBER_ID)).isZero();
    }

    @Test
    void 충전_후_잔액이_한도를_넘으면_충전을_요청할_수_없다() {
        // Given: 이미 45만원이 있음
        agreeAllTerms(MEMBER_ID);
        buyerWalletService.chargeByPayment(MEMBER_ID, "seed-payment", 450_000);

        // When & Then: 6만원을 더하면 51만원
        assertErrorCode(() -> chargeService.request(MEMBER_ID, 60_000), WalletErrorCode.BALANCE_LIMIT_EXCEEDED);
    }

    // 승인

    @Test
    void 승인되면_잔액이_늘고_원장에_한_건_기록되며_충전_건이_완료된다() {
        // Given
        String chargeNo = requestCharge(MEMBER_ID, 10_000);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .willReturn(new TossConfirmResult.Approved(PAYMENT_KEY));

        // When
        ChargeConfirmResult result = chargeConfirmService.confirm(MEMBER_ID, chargeNo, PAYMENT_KEY, 10_000);

        // Then
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(balanceOf(MEMBER_ID)).isEqualTo(10_000);
        assertThat(countLedgers(MEMBER_ID, "CHARGE:" + PAYMENT_KEY)).isEqualTo(1);
        assertThat(paymentKeyOf(chargeNo)).isEqualTo(PAYMENT_KEY);
    }

    @Test
    void 토스_승인을_호출하는_동안에는_트랜잭션이_열려_있지_않다() {
        // Given: 토스가 호출되는 순간 트랜잭션이 열려 있는지 기록한다
        String chargeNo = requestCharge(MEMBER_ID, 10_000);
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .willAnswer(invocation -> {
                    transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return new TossConfirmResult.Approved(PAYMENT_KEY);
                });

        // When
        chargeConfirmService.confirm(MEMBER_ID, chargeNo, PAYMENT_KEY, 10_000);

        // Then
        assertThat(transactionActive).isFalse();
    }

    @Test
    void 금액이_요청과_다르면_토스를_호출하지_않고_실패한다() {
        String chargeNo = requestCharge(MEMBER_ID, 10_000);

        assertErrorCode(() -> chargeConfirmService.confirm(MEMBER_ID, chargeNo, PAYMENT_KEY, 20_000),
                WalletErrorCode.CHARGE_AMOUNT_MISMATCH);
        then(tossPaymentsClient).should(never()).confirm(anyString(), anyString(), anyLong());
        assertThat(statusOf(chargeNo)).isEqualTo("PENDING");
    }

    @Test
    void 타인의_충전_건은_승인할_수_없다() {
        String chargeNo = requestCharge(MEMBER_ID, 10_000);

        assertErrorCode(() -> chargeConfirmService.confirm(OTHER_MEMBER_ID, chargeNo, PAYMENT_KEY, 10_000),
                WalletErrorCode.CHARGE_NOT_FOUND);
        then(tossPaymentsClient).should(never()).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    void 토스가_명확히_거절하면_충전_건이_실패하고_잔액은_그대로다() {
        // Given
        String chargeNo = requestCharge(MEMBER_ID, 10_000);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .willReturn(new TossConfirmResult.Rejected("REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족"));

        // When
        ChargeConfirmResult result = chargeConfirmService.confirm(MEMBER_ID, chargeNo, PAYMENT_KEY, 10_000);

        // Then
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(balanceOrZero(MEMBER_ID)).isZero();
    }

    @Test
    void 토스_응답이_불명확하면_처리_중으로_남고_잔액은_그대로다() {
        // Given: 타임아웃
        String chargeNo = requestCharge(MEMBER_ID, 10_000);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .willReturn(new TossConfirmResult.Unknown("토스 응답 없음"));

        // When
        ChargeConfirmResult result = chargeConfirmService.confirm(MEMBER_ID, chargeNo, PAYMENT_KEY, 10_000);

        // Then: 복구 스케줄러가 처리할 때까지 처리 중으로 남는다
        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(balanceOrZero(MEMBER_ID)).isZero();
    }

    @Test
    void 이미_완료된_충전_건을_다시_승인하면_토스를_다시_호출하지_않는다() {
        // Given
        String chargeNo = requestCharge(MEMBER_ID, 10_000);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .willReturn(new TossConfirmResult.Approved(PAYMENT_KEY));
        chargeConfirmService.confirm(MEMBER_ID, chargeNo, PAYMENT_KEY, 10_000);

        // When: 새로고침으로 같은 승인 요청이 다시 옴
        ChargeConfirmResult result = chargeConfirmService.confirm(MEMBER_ID, chargeNo, PAYMENT_KEY, 10_000);

        // Then
        assertThat(result.status()).isEqualTo("COMPLETED");
        then(tossPaymentsClient).should(times(1)).confirm(anyString(), anyString(), anyLong());
        assertThat(balanceOf(MEMBER_ID)).isEqualTo(10_000);
    }

    @Test
    void 같은_충전_건의_승인이_동시에_와도_토스는_한_번만_호출되고_잔액은_한_번만_는다() throws Exception {
        // Given
        String chargeNo = requestCharge(MEMBER_ID, 10_000);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .willReturn(new TossConfirmResult.Approved(PAYMENT_KEY));
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ChargeConfirmResult>> results = new ArrayList<>();

        try {
            // When: 새로고침·재시도처럼 같은 승인 요청이 동시에 들어옴
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return chargeConfirmService.confirm(MEMBER_ID, chargeNo, PAYMENT_KEY, 10_000);
                }));
            }
            ready.await();
            start.countDown();
            for (Future<ChargeConfirmResult> result : results) {
                result.get(30, TimeUnit.SECONDS); // 예외 없이 모두 현재 상태를 받아야 한다
            }

            // Then
            then(tossPaymentsClient).should(times(1)).confirm(anyString(), anyString(), anyLong());
            assertThat(balanceOf(MEMBER_ID)).isEqualTo(10_000);
            assertThat(countLedgers(MEMBER_ID, "CHARGE:" + PAYMENT_KEY)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 요청_때는_통과했어도_승인_시점에_한도를_넘으면_토스를_호출하지_않는다() {
        // Given: 잔액 0원일 때 30만원 충전 두 건을 요청 (요청 시점에는 둘 다 통과)
        String first = requestCharge(MEMBER_ID, 300_000);
        String second = requestCharge(MEMBER_ID, 300_000);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .willReturn(new TossConfirmResult.Approved(PAYMENT_KEY));
        chargeConfirmService.confirm(MEMBER_ID, first, PAYMENT_KEY, 300_000);

        // When & Then: 첫 충전이 반영되어 잔액 30만원, 두 번째를 더하면 60만원
        assertErrorCode(() -> chargeConfirmService.confirm(MEMBER_ID, second, "payment-key-2", 300_000),
                WalletErrorCode.BALANCE_LIMIT_EXCEEDED);
        then(tossPaymentsClient).should(times(1)).confirm(anyString(), anyString(), anyLong());
        assertThat(statusOf(second)).isEqualTo("PENDING");
    }

    // 결제 실패

    @Test
    void 결제창에서_실패하면_대기_중인_충전_건이_실패로_바뀐다() {
        String chargeNo = requestCharge(MEMBER_ID, 10_000);

        ChargeConfirmResult result = chargeService.fail(MEMBER_ID, chargeNo, "PAY_PROCESS_CANCELED", "사용자 취소");

        assertThat(result.status()).isEqualTo("FAILED");
    }

    @Test
    void 이미_승인이_시작된_충전_건은_결제_실패로_바뀌지_않는다() {
        // Given: 승인 결과가 불명확해 처리 중으로 남은 건
        String chargeNo = requestCharge(MEMBER_ID, 10_000);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .willReturn(new TossConfirmResult.Unknown("토스 응답 없음"));
        chargeConfirmService.confirm(MEMBER_ID, chargeNo, PAYMENT_KEY, 10_000);

        // When: 실패 URL로 다시 들어옴
        ChargeConfirmResult result = chargeService.fail(MEMBER_ID, chargeNo, "PAY_PROCESS_CANCELED", "사용자 취소");

        // Then: 승인됐을 수도 있으므로 실패로 바꾸지 않는다
        assertThat(result.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void 타인의_충전_건은_실패로_바꿀_수_없다() {
        String chargeNo = requestCharge(MEMBER_ID, 10_000);

        assertErrorCode(() -> chargeService.fail(OTHER_MEMBER_ID, chargeNo, "PAY_PROCESS_CANCELED", "사용자 취소"),
                WalletErrorCode.CHARGE_NOT_FOUND);
        assertThat(statusOf(chargeNo)).isEqualTo("PENDING");
    }

    private String requestCharge(Long memberId, long amount) {
        agreeAllTerms(memberId);
        return chargeService.request(memberId, amount).chargeNo();
    }

    private void agreeAllTerms(Long memberId) {
        List<TermsAgreeCommand> commands = Arrays.stream(TermsType.values())
                .map(type -> new TermsAgreeCommand(type.name(), 1))
                .toList();
        termsService.agree(memberId, commands);
    }

    private static void assertErrorCode(ThrowingCallable callable, WalletErrorCode errorCode) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(RestApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    }

    private String statusOf(String chargeNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_charge WHERE charge_no = ?", String.class, chargeNo);
    }

    private String paymentKeyOf(String chargeNo) {
        return jdbcTemplate.queryForObject(
                "SELECT payment_key FROM wallet_charge WHERE charge_no = ?", String.class, chargeNo);
    }

    private long countCharges(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_charge WHERE member_id = ?", Long.class, memberId);
    }

    private long balanceOf(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM buyer_wallet WHERE member_id = ?", Long.class, memberId);
    }

    // 승인이 실패하면 지갑이 만들어지지 않았을 수 있다
    private long balanceOrZero(Long memberId) {
        List<Long> balances = jdbcTemplate.queryForList(
                "SELECT balance FROM buyer_wallet WHERE member_id = ?", Long.class, memberId);
        return balances.isEmpty() ? 0 : balances.get(0);
    }

    private long countLedgers(Long memberId, String referenceKey) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM buyer_wallet_ledger l
                JOIN buyer_wallet w ON l.buyer_wallet_id = w.id
                WHERE w.member_id = ? AND l.reference_key = ?
                """, Long.class, memberId, referenceKey);
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
