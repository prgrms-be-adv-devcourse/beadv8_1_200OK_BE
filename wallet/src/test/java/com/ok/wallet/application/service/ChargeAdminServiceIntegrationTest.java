package com.ok.wallet.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.application.dto.ChargeAdminDetailResult;
import com.ok.wallet.application.dto.ChargeAdminResult;
import com.ok.wallet.application.dto.TermsAgreeCommand;
import com.ok.wallet.application.dto.TossCancelResult;
import com.ok.wallet.application.dto.TossConfirmResult;
import com.ok.wallet.application.dto.TossPaymentLookupResult;
import com.ok.wallet.application.dto.TossPaymentStatus;
import com.ok.wallet.application.port.out.TossPaymentsClient;
import com.ok.wallet.domain.TermsType;
import com.ok.wallet.exception.WalletErrorCode;
import com.ok.wallet.support.WalletTestDataCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// 관리자 처리가 토스 상태와 맞지 않는 선택을 막고, 맞을 때만 충전 건과 잔액을 바꾸는지 실제 DB로 확인한다. 토스 호출만 Mock으로 바꾼다.
// 건마다 트랜잭션이 따로 커밋되어야 하므로 테스트에 @Transactional을 붙이지 않는다.
class ChargeAdminServiceIntegrationTest extends AbstractIntegrationTest {
    private static final Long MEMBER_ID = 8_201L;
    private static final Long ADMIN_ID = 1L;
    private static final Long OTHER_ADMIN_ID = 2L;
    private static final long AMOUNT = 10_000;
    private static final String PAYMENT_KEY = "admin-payment-key-1";
    private static final String REASON = "토스 결제 확인 후 관리자 처리";

    // 복구 시험과 같은 Mock 구성이라 스프링 컨텍스트를 함께 쓴다
    @MockitoBean
    TossPaymentsClient tossPaymentsClient;

    @Autowired
    ChargeAdminService chargeAdminService;

    @Autowired
    ChargeService chargeService;

    @Autowired
    ChargeConfirmService chargeConfirmService;

    @Autowired
    ChargeTransactionService chargeTransaction;

    @Autowired
    TermsService termsService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        new WalletTestDataCleaner(jdbcTemplate).deleteAll();
        deleteTermsAgreements();
    }

    @AfterEach
    void tearDown() {
        deleteTermsAgreements();
        new WalletTestDataCleaner(jdbcTemplate).verifyAndDeleteAll();
    }

    // 목록·상세

    @Test
    void 처리할_건_목록에는_멈춘_건과_승인_뒤_실패해_아직_취소하지_않은_건만_나온다() {
        // given
        String held = heldCharge("admin-key-held");
        String approvedAfterFailure = approvedAfterFailureCharge("admin-key-approved");
        String alreadyCanceled = approvedAfterFailureCharge("admin-key-canceled");
        jdbcTemplate.update("UPDATE wallet_charge SET payment_canceled_at = ? WHERE charge_no = ?", LocalDateTime.now(), alreadyCanceled);
        inProgressCharge("admin-key-checking");
        String failed = inProgressCharge("admin-key-failed");
        jdbcTemplate.update("UPDATE wallet_charge SET status = 'FAILED' WHERE charge_no = ?", failed);

        // when
        List<ChargeAdminResult> sut = chargeAdminService.findTargets();

        // then
        assertThat(sut).extracting(ChargeAdminResult::chargeNo).containsExactly(held, approvedAfterFailure);
    }

    @Test
    void 상세에는_충전_건과_토스_결제_상태가_함께_나온다() {
        // given
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));

        // when
        ChargeAdminDetailResult sut = chargeAdminService.findDetail(chargeNo);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.charge().chargeNo()).isEqualTo(chargeNo);
            softly.assertThat(sut.charge().heldForReview()).isTrue();
            softly.assertThat(sut.tossLookup()).isEqualTo("FOUND");
            softly.assertThat(sut.tossStatus()).isEqualTo("DONE");
            softly.assertThat(sut.tossTotalAmount()).isEqualTo(AMOUNT);
        });
    }

    // 잔액 반영

    @Test
    void 토스가_같은_금액으로_승인한_멈춘_건을_반영하면_완료하고_잔액이_는다() {
        // given
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));

        // when
        ChargeAdminResult sut = chargeAdminService.complete(ADMIN_ID, chargeNo);

        // then
        assertThat(sut.status()).isEqualTo("COMPLETED");
        assertThat(balanceOrZero(MEMBER_ID)).isEqualTo(AMOUNT);
    }

    @ParameterizedTest
    @MethodSource("반영할_수_없는_토스_답")
    void 토스가_같은_금액으로_승인하지_않았으면_반영을_거부하고_그대로_둔다(TossPaymentLookupResult lookup) {
        // given
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, lookup);

        // when & then
        assertThatThrownBy(() -> chargeAdminService.complete(ADMIN_ID, chargeNo))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_TOSS_STATUS_MISMATCH));
        assertHeldUnchanged(chargeNo);
    }

    static Stream<TossPaymentLookupResult> 반영할_수_없는_토스_답() {
        return Stream.of(
                found(PAYMENT_KEY, TossPaymentStatus.ABORTED, AMOUNT),
                found(PAYMENT_KEY, TossPaymentStatus.CANCELED, AMOUNT),
                found(PAYMENT_KEY, TossPaymentStatus.DONE, 20_000),
                new TossPaymentLookupResult.NotFound());
    }

    @Test
    void 복구가_아직_확인_중인_건은_관리자가_처리할_수_없다() {
        // given: 처리 중이지만 다음 확인 시각이 있어 복구가 맡고 있는 건
        String chargeNo = inProgressCharge(PAYMENT_KEY);

        // when & then
        assertThatThrownBy(() -> chargeAdminService.complete(ADMIN_ID, chargeNo))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE));
        then(tossPaymentsClient).should(never()).findPayment(anyString());
    }

    // 실패 처리

    @ParameterizedTest
    @MethodSource("실패로_둘_수_있는_토스_답")
    void 토스가_승인하지_않은_멈춘_건을_실패로_바꾼다(TossPaymentLookupResult lookup) {
        // given
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, lookup);

        // when
        ChargeAdminResult sut = chargeAdminService.fail(ADMIN_ID, chargeNo, REASON);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo("FAILED");
            softly.assertThat(sut.failureCode()).isEqualTo("ADMIN_FAILED");
            softly.assertThat(sut.failureMessage()).isEqualTo(REASON);
            softly.assertThat(balanceOrZero(MEMBER_ID)).isZero();
        });
    }

    static Stream<TossPaymentLookupResult> 실패로_둘_수_있는_토스_답() {
        return Stream.of(
                new TossPaymentLookupResult.NotFound(),
                found(PAYMENT_KEY, TossPaymentStatus.ABORTED, AMOUNT),
                found(PAYMENT_KEY, TossPaymentStatus.EXPIRED, AMOUNT),
                found(PAYMENT_KEY, TossPaymentStatus.CANCELED, AMOUNT));
    }

    @ParameterizedTest
    @MethodSource("실패로_둘_수_없는_토스_답")
    void 토스가_승인했거나_아직_진행_중이면_실패_처리를_거부하고_그대로_둔다(TossPaymentStatus status) {
        // given
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, status, AMOUNT));

        // when & then
        assertThatThrownBy(() -> chargeAdminService.fail(ADMIN_ID, chargeNo, REASON))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_TOSS_STATUS_MISMATCH));
        assertHeldUnchanged(chargeNo);
    }

    static Stream<TossPaymentStatus> 실패로_둘_수_없는_토스_답() {
        return Stream.of(TossPaymentStatus.DONE, TossPaymentStatus.PARTIAL_CANCELED,
                TossPaymentStatus.IN_PROGRESS, TossPaymentStatus.READY);
    }

    @Test
    void 토스_결제_상태를_모르면_처리하지_않는다() {
        // given
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, new TossPaymentLookupResult.Unknown("토스 응답 없음"));

        // when & then
        assertThatThrownBy(() -> chargeAdminService.fail(ADMIN_ID, chargeNo, REASON))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_TOSS_STATUS_UNKNOWN));
        assertHeldUnchanged(chargeNo);
    }

    // 결제 취소

    @Test
    void 멈춘_건의_토스_결제를_취소하면_실패로_바꾸고_취소_시각을_남긴다() {
        // given
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString()))
                .willReturn(new TossCancelResult.Canceled());

        // when
        ChargeAdminResult sut = chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo("FAILED");
            softly.assertThat(sut.failureCode()).isEqualTo("PAYMENT_CANCELED");
            softly.assertThat(sut.paymentCanceledAt()).isNotNull();
            softly.assertThat(balanceOrZero(MEMBER_ID)).isZero();
        });
        ArgumentCaptor<String> idempotencyKey = ArgumentCaptor.forClass(String.class);
        then(tossPaymentsClient).should().cancel(eq(PAYMENT_KEY), eq(chargeNo), eq(REASON), idempotencyKey.capture());
        assertThat(idempotencyKey.getValue()).startsWith("CANCEL-" + chargeNo + "-");
    }

    @Test
    void 승인_뒤_실패한_건의_토스_결제를_취소하면_원래_실패_사유를_두고_취소_시각을_남긴다() {
        // given
        String chargeNo = approvedAfterFailureCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString()))
                .willReturn(new TossCancelResult.Canceled());

        // when
        ChargeAdminResult sut = chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo("FAILED");
            softly.assertThat(sut.failureCode()).isEqualTo("NOT_FOUND_PAYMENT");
            softly.assertThat(sut.paymentCanceledAt()).isNotNull();
        });
        assertThat(chargeAdminService.findTargets()).isEmpty();
    }

    @Test
    void 취소_결과를_모르면_토스에_다시_물어_취소된_것이_확인될_때만_기록한다() {
        // given: 취소 응답은 끊겼지만 토스에서는 취소가 끝났다
        String chargeNo = heldCharge(PAYMENT_KEY);
        given(tossPaymentsClient.findPayment(chargeNo)).willReturn(
                found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT),
                found(PAYMENT_KEY, TossPaymentStatus.CANCELED, AMOUNT));
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString()))
                .willReturn(new TossCancelResult.Unknown("토스 응답 없음"));

        // when
        ChargeAdminResult sut = chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON);

        // then
        assertThat(sut.paymentCanceledAt()).isNotNull();
    }

    @Test
    void 취소_결과를_모르고_토스가_아직_승인_상태면_기록하지_않는다() {
        // given
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString()))
                .willReturn(new TossCancelResult.Unknown("토스 서버 오류 500"));

        // when & then
        assertThatThrownBy(() -> chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_TOSS_STATUS_UNKNOWN));
        assertHeldUnchanged(chargeNo);
    }

    @Test
    void 토스가_취소를_거절하면_기록하지_않는다() {
        // given
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString()))
                .willReturn(new TossCancelResult.Rejected("EXCEED_MAX_REFUND_DUE", "환불 가능한 기간이 지났습니다."));

        // when & then
        assertThatThrownBy(() -> chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_PAYMENT_CANCEL_REJECTED));
        assertHeldUnchanged(chargeNo);
    }

    @Test
    void 결제_취소_중에_다른_관리자가_반영하면_반영을_막아_환불과_잔액_증가가_함께_일어나지_않는다() {
        // given: 토스 취소 응답을 기다리는 사이 다른 관리자가 같은 건을 반영한다. 반영 직전 조회에는 아직 승인 상태로 보인다
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        AtomicReference<RuntimeException> completeError = new AtomicReference<>();
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString())).willAnswer(invocation -> {
            try {
                chargeAdminService.complete(OTHER_ADMIN_ID, chargeNo);
            } catch (RuntimeException e) {
                completeError.set(e);
            }
            return new TossCancelResult.Canceled();
        });

        // when
        ChargeAdminResult sut = chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON);

        // then
        assertThat(completeError.get()).isInstanceOfSatisfying(RestApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE));
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo("FAILED");
            softly.assertThat(sut.paymentCanceledAt()).isNotNull();
            softly.assertThat(balanceOrZero(MEMBER_ID)).isZero();
        });
    }

    @Test
    void 결제_취소_중에_늦게_온_승인_응답은_잔액에_반영하지_않는다() {
        // given: 토스 취소 응답을 기다리는 사이 회원의 승인 요청이 늦게 승인 응답을 받는다
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        AtomicReference<String> lateApprovalStatus = new AtomicReference<>();
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString())).willAnswer(invocation -> {
            lateApprovalStatus.set(chargeTransaction.complete(MEMBER_ID, chargeNo, PAYMENT_KEY).status());
            return new TossCancelResult.Canceled();
        });

        // when
        ChargeAdminResult sut = chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(lateApprovalStatus.get()).isEqualTo("IN_PROGRESS");
            softly.assertThat(sut.status()).isEqualTo("FAILED");
            softly.assertThat(sut.paymentCanceledAt()).isNotNull();
            softly.assertThat(balanceOrZero(MEMBER_ID)).isZero();
        });
    }

    @Test
    void 취소_결과를_모르는_건은_반영과_실패를_막고_결제_취소만_다시_할_수_있다() {
        // given: 취소 결과를 몰라 환불됐을 수도 있는 건
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString()))
                .willReturn(new TossCancelResult.Unknown("토스 응답 없음"), new TossCancelResult.Canceled());
        assertThatThrownBy(() -> chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_TOSS_STATUS_UNKNOWN));

        // when & then
        assertThatThrownBy(() -> chargeAdminService.complete(OTHER_ADMIN_ID, chargeNo))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE));
        assertThatThrownBy(() -> chargeAdminService.fail(OTHER_ADMIN_ID, chargeNo, REASON))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE));
        assertThat(chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON).paymentCanceledAt()).isNotNull();
        assertThat(balanceOrZero(MEMBER_ID)).isZero();
    }

    @Test
    void 토스가_취소를_거절한_건은_사람이_표시를_지워야_다시_반영할_수_있다() {
        // given
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString()))
                .willReturn(new TossCancelResult.Rejected("EXCEED_MAX_REFUND_DUE", "환불 가능한 기간이 지났습니다."));
        assertThatThrownBy(() -> chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_PAYMENT_CANCEL_REJECTED));
        assertThatThrownBy(() -> chargeAdminService.complete(ADMIN_ID, chargeNo))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE));
        // 개발 문서의 절차: 토스에서 승인 상태이고 진행 중인 취소가 없음을 확인한 뒤 표시를 지운다
        jdbcTemplate.update("""
                UPDATE wallet_charge SET payment_cancel_requested_at = NULL
                WHERE charge_no = ? AND status = 'IN_PROGRESS'
                """, chargeNo);

        // when
        ChargeAdminResult sut = chargeAdminService.complete(ADMIN_ID, chargeNo);

        // then
        assertThat(sut.status()).isEqualTo("COMPLETED");
        assertThat(balanceOrZero(MEMBER_ID)).isEqualTo(AMOUNT);
    }

    @Test
    void 다시_시도한_취소가_거절돼도_앞선_취소로_환불됐으면_결제_취소로_기록한다() {
        // given: 앞선 취소는 결과를 몰랐는데 토스에서는 늦게 끝났다. 다시 시도한 취소는 거절된다
        String chargeNo = heldCharge(PAYMENT_KEY);
        given(tossPaymentsClient.findPayment(chargeNo)).willReturn(
                found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT),
                found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT),
                found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT),
                found(PAYMENT_KEY, TossPaymentStatus.CANCELED, AMOUNT));
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString())).willReturn(
                new TossCancelResult.Unknown("토스 응답 없음"),
                new TossCancelResult.Rejected("NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다."));
        assertThatThrownBy(() -> chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_TOSS_STATUS_UNKNOWN));

        // when
        ChargeAdminResult sut = chargeAdminService.cancelPayment(OTHER_ADMIN_ID, chargeNo, REASON);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo("FAILED");
            softly.assertThat(sut.paymentCanceledAt()).isNotNull();
            softly.assertThat(balanceOrZero(MEMBER_ID)).isZero();
        });
    }

    @Test
    void 결과를_모르는_취소가_있는_건에_다시_건_취소가_거절되면_표시를_남겨_반영을_막는다() {
        // given: 앞선 취소는 결과를 모르고, 토스에서 아직 처리 중이라 조회에는 승인 상태로 보인다
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString())).willReturn(
                new TossCancelResult.Unknown("토스 응답 없음"),
                new TossCancelResult.Rejected("NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다."));
        assertThatThrownBy(() -> chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_TOSS_STATUS_UNKNOWN));
        assertThatThrownBy(() -> chargeAdminService.cancelPayment(OTHER_ADMIN_ID, chargeNo, REASON))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_PAYMENT_CANCEL_REJECTED));

        // when & then
        assertThatThrownBy(() -> chargeAdminService.complete(OTHER_ADMIN_ID, chargeNo))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE));
        assertThat(balanceOrZero(MEMBER_ID)).isZero();
    }

    @Test
    void 토스_취소_호출이_예상하지_못한_오류를_내면_결과를_모르는_것으로_보고_표시를_남긴다() {
        // given
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString()))
                .willThrow(new IllegalStateException("응답 변환 실패"));

        // when & then
        assertThatThrownBy(() -> chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_TOSS_STATUS_UNKNOWN));
        assertThatThrownBy(() -> chargeAdminService.complete(OTHER_ADMIN_ID, chargeNo))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE));
    }

    @Test
    void 두_관리자가_동시에_취소해_한쪽이_거절돼도_다른_쪽_취소가_진행_중이면_잔액에_반영하지_않는다() throws Exception {
        // given: 관리자 B의 토스 취소가 진행 중인 사이 관리자 A의 취소가 거절되고 늦은 승인 응답이 들어온 뒤, B의 취소가 성공한다
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        ExecutorService otherAdmin = Executors.newSingleThreadExecutor();
        CountDownLatch otherCancelSent = new CountDownLatch(1);
        CountDownLatch otherCancelMayFinish = new CountDownLatch(1);
        AtomicReference<Future<ChargeAdminResult>> otherCancel = new AtomicReference<>();
        AtomicInteger cancelCalls = new AtomicInteger();
        given(tossPaymentsClient.cancel(anyString(), anyString(), anyString(), anyString())).willAnswer(invocation -> {
            if (cancelCalls.incrementAndGet() == 1) {
                otherCancel.set(otherAdmin.submit(() -> chargeAdminService.cancelPayment(OTHER_ADMIN_ID, chargeNo, REASON)));
                assertThat(otherCancelSent.await(10, TimeUnit.SECONDS)).isTrue();
                return new TossCancelResult.Rejected("NOT_CANCELABLE_PAYMENT", "취소 할 수 없는 결제 입니다.");
            }
            otherCancelSent.countDown();
            assertThat(otherCancelMayFinish.await(10, TimeUnit.SECONDS)).isTrue();
            return new TossCancelResult.Canceled();
        });

        try {
            // when
            assertThatThrownBy(() -> chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON))
                    .isInstanceOfSatisfying(RestApiException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_PAYMENT_CANCEL_REJECTED));
            String lateApprovalStatus = chargeTransaction.complete(MEMBER_ID, chargeNo, PAYMENT_KEY).status();
            otherCancelMayFinish.countDown();
            ChargeAdminResult sut = otherCancel.get().get(10, TimeUnit.SECONDS);

            // then
            assertThat(sut).isNotNull();
            assertSoftly(softly -> {
                softly.assertThat(lateApprovalStatus).isEqualTo("IN_PROGRESS");
                softly.assertThat(sut.status()).isEqualTo("FAILED");
                softly.assertThat(sut.paymentCanceledAt()).isNotNull();
                softly.assertThat(balanceOrZero(MEMBER_ID)).isZero();
            });
        } finally {
            otherCancelMayFinish.countDown();
            otherAdmin.shutdownNow();
        }
    }

    @Test
    void 토스에서_이미_취소된_결제는_다시_취소하지_않고_기록한다() {
        // given: 앞선 시도에서 토스 취소는 됐는데 기록하지 못했거나, 토스 상점관리자에서 취소함
        String chargeNo = heldCharge(PAYMENT_KEY);
        givenLookup(chargeNo, found(PAYMENT_KEY, TossPaymentStatus.CANCELED, AMOUNT));

        // when
        ChargeAdminResult sut = chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON);

        // then
        assertThat(sut.paymentCanceledAt()).isNotNull();
        then(tossPaymentsClient).should(never()).cancel(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 이미_결제를_취소한_건은_다시_처리할_수_없다() {
        // given
        String chargeNo = approvedAfterFailureCharge(PAYMENT_KEY);
        jdbcTemplate.update("UPDATE wallet_charge SET payment_canceled_at = ? WHERE charge_no = ?", LocalDateTime.now(), chargeNo);

        // when & then
        assertThatThrownBy(() -> chargeAdminService.cancelPayment(ADMIN_ID, chargeNo, REASON))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE));
    }

    // 승인 결과를 알 수 없어 처리 중으로 남은 건. 다음 확인 시각이 있어 복구가 맡고 있다
    private String inProgressCharge(String paymentKey) {
        agreeAllTerms();
        String chargeNo = chargeService.request(MEMBER_ID, AMOUNT).chargeNo();
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong()))
                .willReturn(new TossConfirmResult.Unknown("토스 응답 없음"));
        chargeConfirmService.confirm(MEMBER_ID, chargeNo, paymentKey, AMOUNT);
        return chargeNo;
    }

    // 복구가 토스 답만으로 정하지 못해 멈춘 건
    private String heldCharge(String paymentKey) {
        String chargeNo = inProgressCharge(paymentKey);
        jdbcTemplate.update("UPDATE wallet_charge SET next_check_at = NULL WHERE charge_no = ?", chargeNo);
        return chargeNo;
    }

    // 복구가 결제 없음으로 실패 처리한 뒤 늦게 온 승인 응답이 표시를 남긴 건
    private String approvedAfterFailureCharge(String paymentKey) {
        String chargeNo = inProgressCharge(paymentKey);
        jdbcTemplate.update("""
                UPDATE wallet_charge
                SET status = 'FAILED', failure_code = 'NOT_FOUND_PAYMENT', failed_at = ?, approved_after_failure_at = ?
                WHERE charge_no = ?
                """, LocalDateTime.now(), LocalDateTime.now(), chargeNo);
        return chargeNo;
    }

    private void givenLookup(String chargeNo, TossPaymentLookupResult result) {
        given(tossPaymentsClient.findPayment(chargeNo)).willReturn(result);
    }

    private static TossPaymentLookupResult.Found found(String paymentKey, TossPaymentStatus status, long totalAmount) {
        return new TossPaymentLookupResult.Found(paymentKey, status, totalAmount);
    }

    private void assertHeldUnchanged(String chargeNo) {
        assertSoftly(softly -> {
            softly.assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM wallet_charge WHERE charge_no = ?", String.class, chargeNo)).isEqualTo("IN_PROGRESS");
            softly.assertThat(jdbcTemplate.queryForObject(
                    "SELECT payment_canceled_at FROM wallet_charge WHERE charge_no = ?", LocalDateTime.class, chargeNo)).isNull();
            softly.assertThat(balanceOrZero(MEMBER_ID)).isZero();
        });
    }

    private long balanceOrZero(Long memberId) {
        List<Long> balances = jdbcTemplate.queryForList(
                "SELECT balance FROM buyer_wallet WHERE member_id = ?", Long.class, memberId);
        return balances.isEmpty() ? 0 : balances.get(0);
    }

    private void agreeAllTerms() {
        List<TermsAgreeCommand> commands = Arrays.stream(TermsType.values())
                .map(type -> new TermsAgreeCommand(type.name(), 1))
                .toList();
        termsService.agree(MEMBER_ID, commands);
    }

    private void deleteTermsAgreements() {
        jdbcTemplate.update("DELETE FROM wallet_terms_agreement WHERE member_id = ?", MEMBER_ID);
    }
}
