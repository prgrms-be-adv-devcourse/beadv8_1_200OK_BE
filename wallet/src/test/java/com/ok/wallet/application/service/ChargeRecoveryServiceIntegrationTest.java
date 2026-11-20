package com.ok.wallet.application.service;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.application.dto.ChargeConfirmResult;
import com.ok.wallet.application.dto.ChargeRecoveryResult;
import com.ok.wallet.application.dto.TermsAgreeCommand;
import com.ok.wallet.application.dto.TossConfirmResult;
import com.ok.wallet.application.dto.TossPaymentLookupResult;
import com.ok.wallet.application.dto.TossPaymentStatus;
import com.ok.wallet.application.port.out.TossPaymentsClient;
import com.ok.wallet.domain.TermsType;
import com.ok.wallet.support.WalletTestCharger;
import com.ok.wallet.support.WalletTestDataCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

// 복구가 토스 조회 결과에 따라 충전 건을 끝내는지 실제 DB로 확인하고, 토스 호출만 Mock으로 바꾼다.
// 건마다 트랜잭션이 따로 커밋되어야 하므로 테스트에 @Transactional을 붙이지 않는다.
class ChargeRecoveryServiceIntegrationTest extends AbstractIntegrationTest {
    private static final Long MEMBER_ID = 8_101L;
    private static final Long OTHER_MEMBER_ID = 8_102L;
    private static final long AMOUNT = 10_000;
    private static final String PAYMENT_KEY = "recovery-payment-key-1";

    @MockitoBean
    TossPaymentsClient tossPaymentsClient;

    @Autowired
    ChargeRecoveryService chargeRecoveryService;

    @Autowired
    ChargeService chargeService;

    @Autowired
    ChargeConfirmService chargeConfirmService;

    @Autowired
    ChargeTransactionService chargeTransactionService;

    @Autowired
    TermsService termsService;

    @Autowired
    BuyerWalletService buyerWalletService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private WalletTestCharger charger;

    @BeforeEach
    void setUp() {
        charger = new WalletTestCharger(jdbcTemplate, buyerWalletService);
        new WalletTestDataCleaner(jdbcTemplate).deleteAll();
        deleteTermsAgreements();
    }

    @AfterEach
    void tearDown() {
        deleteTermsAgreements();
        new WalletTestDataCleaner(jdbcTemplate).verifyAndDeleteAll();
    }

    @Test
    void 토스가_같은_결제_키와_금액으로_승인했으면_완료하고_잔액에_반영한다() {
        // given
        String chargeNo = dueInProgressCharge(MEMBER_ID, PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut.completed()).isEqualTo(1);
        assertSoftly(softly -> {
            softly.assertThat(statusOf(chargeNo)).isEqualTo("COMPLETED");
            softly.assertThat(balanceOf(MEMBER_ID)).isEqualTo(AMOUNT);
            softly.assertThat(countLedgers(MEMBER_ID, "CHARGE:" + PAYMENT_KEY)).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @MethodSource("충전_건과_맞지_않는_승인")
    void 토스_승인이_충전_건과_맞지_않으면_반영하지_않고_멈춘다(String paymentKey, long totalAmount) {
        // given
        String chargeNo = dueInProgressCharge(MEMBER_ID, PAYMENT_KEY);
        givenLookup(chargeNo, found(paymentKey, TossPaymentStatus.DONE, totalAmount));

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut.held()).isEqualTo(1);
        assertHeld(chargeNo);
        assertThat(balanceOrZero(MEMBER_ID)).isZero();
    }

    static Stream<Arguments> 충전_건과_맞지_않는_승인() {
        return Stream.of(
                Arguments.of("other-payment-key", AMOUNT),
                Arguments.of(PAYMENT_KEY, AMOUNT + 1),
                Arguments.of(null, AMOUNT));
    }

    @ParameterizedTest
    @MethodSource("승인되지_않은_결제_상태")
    void 토스가_승인하지_않았다고_답하면_실패로_바꾼다(TossPaymentStatus status) {
        // given
        String chargeNo = dueInProgressCharge(MEMBER_ID, PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, status, AMOUNT));

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut.failed()).isEqualTo(1);
        assertSoftly(softly -> {
            softly.assertThat(statusOf(chargeNo)).isEqualTo("FAILED");
            softly.assertThat(failureCodeOf(chargeNo)).isEqualTo(status.name());
        });
    }

    static Stream<TossPaymentStatus> 승인되지_않은_결제_상태() {
        return Stream.of(TossPaymentStatus.ABORTED, TossPaymentStatus.EXPIRED);
    }

    @ParameterizedTest
    @MethodSource("결제_키가_다른_승인되지_않은_결제")
    void 승인되지_않은_결제의_결제_키가_충전_건과_다르면_실패로_바꾸지_않고_멈춘다(String paymentKey, TossPaymentStatus status) {
        // given: 같은 충전 번호의 다른 결제 시도(승인 실패)를 토스가 돌려준 경우. 승인을 요청한 결제의 상태는 이 조회로 알 수 없다
        String chargeNo = dueInProgressCharge(MEMBER_ID, PAYMENT_KEY);
        givenLookup(chargeNo, found(paymentKey, status, AMOUNT));

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut.held()).isEqualTo(1);
        assertHeld(chargeNo);
    }

    static Stream<Arguments> 결제_키가_다른_승인되지_않은_결제() {
        return Stream.of(
                Arguments.of("later-attempt-key", TossPaymentStatus.ABORTED),
                Arguments.of("later-attempt-key", TossPaymentStatus.EXPIRED),
                Arguments.of(null, TossPaymentStatus.ABORTED));
    }

    @ParameterizedTest
    @MethodSource("자동으로_정할_수_없는_결제_상태")
    void 토스_답만으로_정할_수_없는_상태면_멈추고_사람_확인을_기다린다(TossPaymentStatus status) {
        // given
        String chargeNo = dueInProgressCharge(MEMBER_ID, PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, status, AMOUNT));

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut.held()).isEqualTo(1);
        assertHeld(chargeNo);
    }

    static Stream<TossPaymentStatus> 자동으로_정할_수_없는_결제_상태() {
        return Stream.of(TossPaymentStatus.CANCELED, TossPaymentStatus.PARTIAL_CANCELED,
                TossPaymentStatus.WAITING_FOR_DEPOSIT);
    }

    @ParameterizedTest
    @MethodSource("아직_알_수_없는_조회_결과")
    void 아직_알_수_없으면_처리_중으로_두고_10분_뒤에_다시_확인한다(TossPaymentLookupResult lookup) {
        // given
        String chargeNo = dueInProgressCharge(MEMBER_ID, PAYMENT_KEY);
        givenLookup(chargeNo, lookup);

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut.retried()).isEqualTo(1);
        assertSoftly(softly -> {
            softly.assertThat(statusOf(chargeNo)).isEqualTo("IN_PROGRESS");
            softly.assertThat(checkCountOf(chargeNo)).isEqualTo(1);
            softly.assertThat(minutesUntilNextCheck(chargeNo)).isEqualTo(10);
        });
    }

    static Stream<TossPaymentLookupResult> 아직_알_수_없는_조회_결과() {
        return Stream.of(
                found(PAYMENT_KEY, TossPaymentStatus.READY, AMOUNT),
                found(PAYMENT_KEY, TossPaymentStatus.IN_PROGRESS, AMOUNT),
                new TossPaymentLookupResult.Unknown("토스 응답 없음"),
                // 충전 요청 뒤 1시간이 지나지 않았으면 결제가 아직 만들어지는 중일 수 있다
                new TossPaymentLookupResult.NotFound());
    }

    @Test
    void 충전_요청_뒤_1시간이_지나도_토스에_결제가_없으면_실패로_바꾼다() {
        // given
        String chargeNo = dueInProgressCharge(MEMBER_ID, PAYMENT_KEY);
        jdbcTemplate.update("UPDATE wallet_charge SET created_at = ? WHERE charge_no = ?",
                LocalDateTime.now().minusHours(1).minusMinutes(1), chargeNo);
        givenLookup(chargeNo, new TossPaymentLookupResult.NotFound());

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut.failed()).isEqualTo(1);
        assertSoftly(softly -> {
            softly.assertThat(statusOf(chargeNo)).isEqualTo("FAILED");
            softly.assertThat(failureCodeOf(chargeNo)).isEqualTo("NOT_FOUND_PAYMENT");
        });
    }

    @Test
    void 열_번째_확인에도_알_수_없으면_멈춘다() {
        // given: 이미 아홉 번 확인함
        String chargeNo = dueInProgressCharge(MEMBER_ID, PAYMENT_KEY);
        jdbcTemplate.update("UPDATE wallet_charge SET check_count = 9 WHERE charge_no = ?", chargeNo);
        givenLookup(chargeNo, new TossPaymentLookupResult.Unknown("토스 응답 없음"));

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut.held()).isEqualTo(1);
        assertHeld(chargeNo);
        assertThat(checkCountOf(chargeNo)).isEqualTo(10);
    }

    @Test
    void 확인할_때가_되지_않았거나_멈춘_건은_토스에_묻지_않는다() {
        // given: 첫 확인 전(승인 직후 5분 안)인 건과 사람 확인을 기다리는 건
        String notDue = inProgressCharge(MEMBER_ID, PAYMENT_KEY);
        String held = inProgressCharge(OTHER_MEMBER_ID, "recovery-payment-key-2");
        jdbcTemplate.update("UPDATE wallet_charge SET next_check_at = NULL WHERE charge_no = ?", held);

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut.checked()).isZero();
        then(tossPaymentsClient).should(never()).findPayment(anyString());
        assertThat(statusOf(notDue)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void 한_건의_반영이_실패해도_다음_건은_처리하고_실패한_건은_다음에_다시_확인한다() {
        // given: 첫 건의 결제 키가 다른 지갑에 이미 반영되어 있어 잔액 반영이 유니크 제약에 걸린다
        // (충전 건의 결제 키는 유니크라 다른 충전 건으로는 만들 수 없어, 원장만 다른 지갑에 넣는다)
        String broken = dueInProgressCharge(MEMBER_ID, PAYMENT_KEY);
        buyerWalletService.chargeByPayment(OTHER_MEMBER_ID, PAYMENT_KEY, AMOUNT);
        String normal = dueInProgressCharge(MEMBER_ID, "recovery-payment-key-2");
        givenLookup(broken, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        givenLookup(normal, found("recovery-payment-key-2", TossPaymentStatus.DONE, AMOUNT));

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut.completed()).isEqualTo(1);
        assertThat(sut.retried()).isEqualTo(1);
        assertSoftly(softly -> {
            softly.assertThat(statusOf(broken)).isEqualTo("IN_PROGRESS");
            softly.assertThat(minutesUntilNextCheck(broken)).isEqualTo(10);
            softly.assertThat(statusOf(normal)).isEqualTo("COMPLETED");
            softly.assertThat(balanceOf(MEMBER_ID)).isEqualTo(AMOUNT);
        });
        // 일부러 넣은 충전 건 없는 원장은 정리 단계의 잔액·원장 대조에 걸리므로 지운다
        jdbcTemplate.update("""
                DELETE l FROM buyer_wallet_ledger l JOIN buyer_wallet w ON l.buyer_wallet_id = w.id
                WHERE w.member_id = ?
                """, OTHER_MEMBER_ID);
        jdbcTemplate.update("UPDATE buyer_wallet SET balance = 0 WHERE member_id = ?", OTHER_MEMBER_ID);
    }

    @Test
    void 두_서버의_복구가_동시에_돌아도_한_건은_한_번만_토스에_묻는다() throws Exception {
        // given
        String first = dueInProgressCharge(MEMBER_ID, PAYMENT_KEY);
        String second = dueInProgressCharge(OTHER_MEMBER_ID, "recovery-payment-key-2");
        givenLookup(first, new TossPaymentLookupResult.Unknown("토스 응답 없음"));
        givenLookup(second, new TossPaymentLookupResult.Unknown("토스 응답 없음"));

        // when
        List<ChargeRecoveryResult> results = runConcurrently(4, chargeRecoveryService::recover);

        // then: 맡은 쪽만 묻고, 확인 횟수도 한 번만 는다
        assertThat(results.stream().mapToInt(ChargeRecoveryResult::checked).sum()).isEqualTo(2);
        then(tossPaymentsClient).should(times(1)).findPayment(first);
        then(tossPaymentsClient).should(times(1)).findPayment(second);
        assertThat(checkCountOf(first)).isEqualTo(1);
        assertThat(checkCountOf(second)).isEqualTo(1);
    }

    @Test
    void 복구가_먼저_완료한_뒤_늦게_온_승인_응답은_잔액을_다시_늘리지_않는다() throws Exception {
        // given: 승인 요청이 토스 응답을 기다리는 동안 복구가 같은 건의 승인을 확인해 완료한다
        String chargeNo = requestCharge(MEMBER_ID);
        LateTossResponse late = confirmWaitingForToss(chargeNo, new TossConfirmResult.Approved(PAYMENT_KEY));
        makeDue(chargeNo);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        assertThat(chargeRecoveryService.recover().completed()).isEqualTo(1);

        // when: 늦게 온 승인 응답을 반영한다
        String sut = late.release().status();

        // then
        assertThat(sut).isEqualTo("COMPLETED");
        assertSoftly(softly -> {
            softly.assertThat(statusOf(chargeNo)).isEqualTo("COMPLETED");
            softly.assertThat(balanceOf(MEMBER_ID)).isEqualTo(AMOUNT);
            softly.assertThat(countLedgers(MEMBER_ID, "CHARGE:" + PAYMENT_KEY)).isEqualTo(1);
        });
    }

    @Test
    void 복구가_먼저_완료한_뒤_늦게_온_거절_응답은_완료를_실패로_덮지_않는다() throws Exception {
        // given
        String chargeNo = requestCharge(MEMBER_ID);
        LateTossResponse late = confirmWaitingForToss(chargeNo,
                new TossConfirmResult.Rejected("REJECT_CARD_COMPANY", "카드사 거절"));
        makeDue(chargeNo);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        chargeRecoveryService.recover();

        // when
        String sut = late.release().status();

        // then
        assertThat(sut).isEqualTo("COMPLETED");
        assertSoftly(softly -> {
            softly.assertThat(statusOf(chargeNo)).isEqualTo("COMPLETED");
            softly.assertThat(balanceOf(MEMBER_ID)).isEqualTo(AMOUNT);
        });
    }

    @Test
    void 실패로_처리된_뒤_늦게_온_승인_응답은_잔액에_넣지_않고_관리자_처리_대상으로_남긴다() throws Exception {
        // given: 승인 요청이 토스 응답을 기다리는 동안 복구가 같은 건을 실패로 끝낸다
        String chargeNo = requestCharge(MEMBER_ID);
        LateTossResponse late = confirmWaitingForToss(chargeNo, new TossConfirmResult.Approved(PAYMENT_KEY));
        chargeTransactionService.failApproval(MEMBER_ID, chargeNo, "NOT_FOUND_PAYMENT", "토스에 결제가 없고 승인 가능 시간이 지남");

        // when: 늦게 온 승인 응답을 반영한다
        String sut = late.release().status();

        // then
        assertThat(sut).isEqualTo("FAILED");
        assertSoftly(softly -> {
            softly.assertThat(statusOf(chargeNo)).isEqualTo("FAILED");
            softly.assertThat(balanceOrZero(MEMBER_ID)).isZero();
            softly.assertThat(approvedAfterFailureAtOf(chargeNo)).isNotNull();
        });
    }

    @Test
    void 복구와_늦게_온_승인_응답이_동시에_완료해도_어느_쪽도_오류로_끝나지_않고_잔액은_한_번만_는다() throws Exception {
        // given
        String chargeNo = requestCharge(MEMBER_ID);
        LateTossResponse late = confirmWaitingForToss(chargeNo, new TossConfirmResult.Approved(PAYMENT_KEY));
        makeDue(chargeNo);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));

        // when: 승인 응답을 풀어 주는 것과 복구를 같은 순간에 시작한다
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String confirmStatus;
        ChargeRecoveryResult recoveryResult;
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<ChargeRecoveryResult> recovery = executor.submit(() -> {
                start.await();
                return chargeRecoveryService.recover();
            });
            start.countDown();
            confirmStatus = late.release().status();
            recoveryResult = recovery.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // then: 잔액이 두 번 늘지 않게 막는 마지막 안전망은 원장의 중복 금지 제약이다. 거기까지 가면 진 쪽이
        // 오류로 끝나(승인 응답은 처리 중으로 답하고, 복구는 반영 실패로 다시 확인) 잔액만 보면 놓친다.
        // 승인 응답이 먼저 끝나면 복구는 고를 건이 없으므로, 복구가 완료한 수는 보지 않는다
        assertSoftly(softly -> {
            softly.assertThat(confirmStatus).isEqualTo("COMPLETED");
            softly.assertThat(recoveryResult.retried()).isZero();
        });
        assertSoftly(softly -> {
            softly.assertThat(statusOf(chargeNo)).isEqualTo("COMPLETED");
            softly.assertThat(balanceOf(MEMBER_ID)).isEqualTo(AMOUNT);
            softly.assertThat(countLedgers(MEMBER_ID, "CHARGE:" + PAYMENT_KEY)).isEqualTo(1);
        });
    }

    @Test
    void 토스에_묻는_사이_승인_응답이_실패로_끝낸_건은_복구가_완료로_세지_않는다() {
        // given: 복구가 토스 답(승인)을 받기 전에 늦게 온 거절 응답이 같은 건을 실패로 끝낸다
        String chargeNo = dueInProgressCharge(MEMBER_ID, PAYMENT_KEY);
        given(tossPaymentsClient.findPayment(chargeNo)).willAnswer(invocation -> {
            chargeTransactionService.failApproval(MEMBER_ID, chargeNo, "REJECT_CARD_COMPANY", "카드사 거절");
            return found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT);
        });

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.alreadyHandled()).isEqualTo(1);
            softly.assertThat(sut.completed()).isZero();
            softly.assertThat(statusOf(chargeNo)).isEqualTo("FAILED");
            softly.assertThat(balanceOrZero(MEMBER_ID)).isZero();
        });
    }

    @Test
    void 토스에_묻는_사이_승인_응답이_완료한_건은_복구가_실패로_세지_않는다() {
        // given: 복구가 토스 답(승인 실패)을 받기 전에 늦게 온 승인 응답이 같은 건을 완료한다
        String chargeNo = dueInProgressCharge(MEMBER_ID, PAYMENT_KEY);
        given(tossPaymentsClient.findPayment(chargeNo)).willAnswer(invocation -> {
            chargeTransactionService.complete(MEMBER_ID, chargeNo, PAYMENT_KEY);
            return found(PAYMENT_KEY, TossPaymentStatus.ABORTED, AMOUNT);
        });

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.alreadyHandled()).isEqualTo(1);
            softly.assertThat(sut.failed()).isZero();
            softly.assertThat(statusOf(chargeNo)).isEqualTo("COMPLETED");
            softly.assertThat(balanceOf(MEMBER_ID)).isEqualTo(AMOUNT);
        });
    }

    @Test
    void 요청_뒤_3시간이_지나도_처리_중인_건만_오래_남은_건으로_알린다() {
        // given: 3시간 넘게 처리 중인 건, 막 처리 중이 된 건, 3시간 넘었지만 완료된 건
        String longInProgress = inProgressCharge(MEMBER_ID, PAYMENT_KEY);
        String recent = inProgressCharge(OTHER_MEMBER_ID, "recovery-payment-key-2");
        String completedOld = dueInProgressCharge(OTHER_MEMBER_ID, "recovery-payment-key-3");
        givenLookup(completedOld, found("recovery-payment-key-3", TossPaymentStatus.DONE, AMOUNT));
        chargeRecoveryService.recover();
        for (String chargeNo : List.of(longInProgress, completedOld)) {
            jdbcTemplate.update("UPDATE wallet_charge SET created_at = ? WHERE charge_no = ?",
                    LocalDateTime.now().minusHours(3).minusMinutes(1), chargeNo);
        }

        // when
        List<String> sut = chargeRecoveryService.findLongInProgressChargeNos();

        // then
        assertThat(sut).containsExactly(longInProgress);
        assertThat(statusOf(recent)).isEqualTo("IN_PROGRESS");
    }

    // 승인 결과를 알 수 없어 처리 중으로 남은 건을 만들고, 확인할 때가 되게 한다
    private String dueInProgressCharge(Long memberId, String paymentKey) {
        String chargeNo = inProgressCharge(memberId, paymentKey);
        jdbcTemplate.update("UPDATE wallet_charge SET next_check_at = ? WHERE charge_no = ?",
                LocalDateTime.now().minusMinutes(1), chargeNo);
        return chargeNo;
    }

    private void makeDue(String chargeNo) {
        jdbcTemplate.update("UPDATE wallet_charge SET next_check_at = ? WHERE charge_no = ?",
                LocalDateTime.now().minusMinutes(1), chargeNo);
    }

    private String requestCharge(Long memberId) {
        agreeAllTerms(memberId);
        return chargeService.request(memberId, AMOUNT).chargeNo();
    }

    // 승인 요청을 다른 스레드에서 시작해, 충전 건이 처리 중이 되고 토스 응답을 기다리는 데서 멈춘다.
    // release()를 부르면 그때 토스 응답(response)이 온 것처럼 이어서 반영하고 승인 결과를 돌려준다
    private LateTossResponse confirmWaitingForToss(String chargeNo, TossConfirmResult response) throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch respond = new CountDownLatch(1);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong())).willAnswer(invocation -> {
            waiting.countDown();
            respond.await(30, TimeUnit.SECONDS);
            return response;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ChargeConfirmResult> confirm = executor.submit(
                () -> chargeConfirmService.confirm(MEMBER_ID, chargeNo, PAYMENT_KEY, AMOUNT));
        assertThat(waiting.await(30, TimeUnit.SECONDS)).isTrue();
        return new LateTossResponse(respond, confirm, executor);
    }

    private record LateTossResponse(CountDownLatch respond, Future<ChargeConfirmResult> confirm, ExecutorService executor) {
        ChargeConfirmResult release() throws Exception {
            try {
                respond.countDown();
                return confirm.get(30, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private String inProgressCharge(Long memberId, String paymentKey) {
        agreeAllTerms(memberId);
        String chargeNo = chargeService.request(memberId, AMOUNT).chargeNo();
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .willReturn(new TossConfirmResult.Unknown("토스 응답 없음"));
        chargeConfirmService.confirm(memberId, chargeNo, paymentKey, AMOUNT);
        return chargeNo;
    }

    private void givenLookup(String chargeNo, TossPaymentLookupResult result) {
        given(tossPaymentsClient.findPayment(chargeNo)).willReturn(result);
    }

    private static TossPaymentLookupResult.Found found(String paymentKey, TossPaymentStatus status, long totalAmount) {
        return new TossPaymentLookupResult.Found(paymentKey, status, totalAmount);
    }

    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = Stream.<Callable<T>>generate(() -> () -> {
                start.await();
                return task.call();
            }).limit(threads).map(executor::submit).toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertHeld(String chargeNo) {
        assertSoftly(softly -> {
            softly.assertThat(statusOf(chargeNo)).isEqualTo("IN_PROGRESS");
            softly.assertThat(nextCheckAtOf(chargeNo)).isNull();
        });
    }

    private String statusOf(String chargeNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM wallet_charge WHERE charge_no = ?", String.class, chargeNo);
    }

    private String failureCodeOf(String chargeNo) {
        return jdbcTemplate.queryForObject(
                "SELECT failure_code FROM wallet_charge WHERE charge_no = ?", String.class, chargeNo);
    }

    private LocalDateTime approvedAfterFailureAtOf(String chargeNo) {
        return jdbcTemplate.queryForObject("SELECT approved_after_failure_at FROM wallet_charge WHERE charge_no = ?",
                LocalDateTime.class, chargeNo);
    }

    private int checkCountOf(String chargeNo) {
        return jdbcTemplate.queryForObject(
                "SELECT check_count FROM wallet_charge WHERE charge_no = ?", Integer.class, chargeNo);
    }

    private LocalDateTime nextCheckAtOf(String chargeNo) {
        return jdbcTemplate.queryForObject(
                "SELECT next_check_at FROM wallet_charge WHERE charge_no = ?", LocalDateTime.class, chargeNo);
    }

    // 다음 확인 시각은 맡은 시각(수정 시각) 기준으로 미룬다
    private long minutesUntilNextCheck(String chargeNo) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT TIMESTAMPDIFF(MINUTE, updated_at, next_check_at) AS minutes FROM wallet_charge WHERE charge_no = ?",
                chargeNo);
        return ((Number) row.get("minutes")).longValue();
    }

    private long balanceOf(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM buyer_wallet WHERE member_id = ?", Long.class, memberId);
    }

    // 승인을 반영하지 않으면 지갑만 있고 잔액은 0원이다
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

    private void agreeAllTerms(Long memberId) {
        List<TermsAgreeCommand> commands = Arrays.stream(TermsType.values())
                .map(type -> new TermsAgreeCommand(type.name(), 1))
                .toList();
        termsService.agree(memberId, commands);
    }

    private void deleteTermsAgreements() {
        for (Long memberId : List.of(MEMBER_ID, OTHER_MEMBER_ID)) {
            jdbcTemplate.update("DELETE FROM wallet_terms_agreement WHERE member_id = ?", memberId);
        }
    }
}
