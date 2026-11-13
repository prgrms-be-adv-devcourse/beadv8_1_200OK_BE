package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.in.WalletRefundCompletedCommand;
import com.ok.refund.application.port.in.WalletRefundFailedCommand;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.StockRelease;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundFailedStep;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

// 재고 복구 실패 기록은 RefundStepRunner가 같은 영속 포트로 하므로 진짜 틀을 쓴다(PRODUCT 단계가 실제로 기록되는지 보려고)
@ExtendWith(MockitoExtension.class)
class HandleWalletRefundResultServiceTest {

    private static final Long REFUND_ID = 7L;
    private static final Long ORDER_ID = 10L;
    private static final Long BUYER_ID = 1L;
    // 환불 금액: 단가 10,000 x 2개, 배송비 0
    private static final long AMOUNT = 20_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 10, 0);

    @Mock
    RefundPersistencePort refundPersistencePort;
    @Mock
    ProductStockReleasePort productStockReleasePort;

    HandleWalletRefundResultService sut;

    @BeforeEach
    void setUp() {
        NoOpTransactionManager transactionManager = new NoOpTransactionManager();
        sut = new HandleWalletRefundResultService(refundPersistencePort, productStockReleasePort,
                new RefundStepRunner(refundPersistencePort, transactionManager), CLOCK, transactionManager);
    }

    // ---- 완료 ----

    @Test
    void 완료_메시지가_금액과_주문이_같으면_재고를_되돌리고_완료로_바꾼다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));

        // when
        sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT));

        // then
        ArgumentCaptor<StockRelease> release = ArgumentCaptor.forClass(StockRelease.class);
        then(productStockReleasePort).should().releaseStock(release.capture());
        assertThat(release.getValue()).isEqualTo(new StockRelease(REFUND_ID, ORDER_ID, 55L, 2));
        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().update(saved.capture());
        assertThat(saved.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getValue().getStatus()).isEqualTo(RefundStatus.COMPLETED);
            softly.assertThat(saved.getValue().getCompletedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 이미_완료된_환불의_완료_메시지는_넘기고_재고를_다시_부르지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.COMPLETED, null, null)));

        // when
        sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT));

        // then
        then(productStockReleasePort).should(never()).releaseStock(any());
        then(refundPersistencePort).should(never()).update(any());
    }

    @Test
    void 읽은_뒤_잠그기_전에_완료되면_완료로_다시_바꾸지_않는다() {
        // given: 재고 복구 사이에 같은 메시지가 먼저 완료한 경우
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.COMPLETED, null, null)));

        // when
        sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT));

        // then
        then(refundPersistencePort).should(never()).update(any());
    }

    @Test
    void 완료_메시지의_환불이_없으면_넘긴다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.empty());

        // when
        sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT));

        // then
        then(productStockReleasePort).should(never()).releaseStock(any());
        then(refundPersistencePort).should(never()).update(any());
    }

    @ParameterizedTest
    @MethodSource("처리_중이_아닌_상태")
    void 처리_중이_아닌_환불의_완료_메시지는_넘긴다(RefundStatus status) {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(status, null, null)));

        // when
        sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT));

        // then
        then(productStockReleasePort).should(never()).releaseStock(any());
        then(refundPersistencePort).should(never()).update(any());
    }

    static Stream<RefundStatus> 처리_중이_아닌_상태() {
        return Stream.of(RefundStatus.REQUESTED, RefundStatus.REJECTED, RefundStatus.WITHDRAWN);
    }

    @ParameterizedTest
    @MethodSource("어긋난_금액과_주문")
    void 완료_메시지의_금액이나_주문이_다르면_완료하지_않고_불일치를_기록한다(Long orderId, long amount) {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));

        // when
        sut.completeAfterWalletRefund(completed(orderId, amount));

        // then
        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().update(saved.capture());
        assertThat(saved.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getValue().getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(saved.getValue().getFailedStep()).isEqualTo(RefundFailedStep.WALLET);
            softly.assertThat(saved.getValue().getFailureCode()).isEqualTo("RESULT_MISMATCH");
        });
        then(productStockReleasePort).should(never()).releaseStock(any());
    }

    static Stream<Arguments> 어긋난_금액과_주문() {
        return Stream.of(
                Arguments.of(ORDER_ID, AMOUNT - 1),
                Arguments.of(ORDER_ID, AMOUNT + 1),
                Arguments.of(99L, AMOUNT));
    }

    @Test
    void 재고_복구가_실패하면_PRODUCT_실패를_기록하고_예외를_다시_던진다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));
        willThrow(new RestApiException(RefundErrorCode.PRODUCT_API_FAILED)).given(productStockReleasePort).releaseStock(any());

        // when & then
        assertThatThrownBy(() -> sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.PRODUCT_API_FAILED));
        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().update(saved.capture());
        assertThat(saved.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getValue().getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(saved.getValue().getFailedStep()).isEqualTo(RefundFailedStep.PRODUCT);
            softly.assertThat(saved.getValue().getFailureCode()).isEqualTo("PRODUCT_API_FAILED");
        });
    }

    // ---- 실패 ----

    @Test
    void 실패_메시지는_WALLET_단계와_이유를_기록하고_처리_중으로_둔다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));

        // when
        sut.recordWalletRefundFailure(failed("INSUFFICIENT_BALANCE"));

        // then
        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().update(saved.capture());
        assertThat(saved.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getValue().getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(saved.getValue().getFailedStep()).isEqualTo(RefundFailedStep.WALLET);
            softly.assertThat(saved.getValue().getFailureCode()).isEqualTo("INSUFFICIENT_BALANCE");
        });
    }

    @Test
    void 같은_실패가_이미_기록돼_있으면_넘긴다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING, RefundFailedStep.WALLET, "INSUFFICIENT_BALANCE")));

        // when
        sut.recordWalletRefundFailure(failed("INSUFFICIENT_BALANCE"));

        // then
        then(refundPersistencePort).should(never()).findByIdForUpdate(any());
        then(refundPersistencePort).should(never()).update(any());
    }

    @Test
    void 다른_이유의_실패는_마지막_실패로_덮어쓴다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING, RefundFailedStep.WALLET, "INSUFFICIENT_BALANCE")));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING, RefundFailedStep.WALLET, "INSUFFICIENT_BALANCE")));

        // when
        sut.recordWalletRefundFailure(failed("WALLET_FROZEN"));

        // then
        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().update(saved.capture());
        assertThat(saved.getValue().getFailureCode()).isEqualTo("WALLET_FROZEN");
    }

    @Test
    void 실패_메시지의_환불이_없으면_넘긴다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.empty());

        // when
        sut.recordWalletRefundFailure(failed("INSUFFICIENT_BALANCE"));

        // then
        then(refundPersistencePort).should(never()).update(any());
    }

    @ParameterizedTest
    @MethodSource("처리_중이_아닌_상태")
    void 처리_중이_아닌_환불의_실패_메시지는_넘긴다(RefundStatus status) {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(status, null, null)));

        // when
        sut.recordWalletRefundFailure(failed("INSUFFICIENT_BALANCE"));

        // then
        then(refundPersistencePort).should(never()).update(any());
    }

    // 완료된 환불에 실패가 오면 중복이 아니라 앞선 완료 결과와 모순이다. ERROR로 남기되 상태는 그대로 둔다
    @Test
    void 이미_완료된_환불의_실패_메시지는_기록하지_않고_넘긴다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.COMPLETED, null, null)));

        // when
        sut.recordWalletRefundFailure(failed("INSUFFICIENT_BALANCE"));

        // then
        then(refundPersistencePort).should(never()).findByIdForUpdate(any());
        then(refundPersistencePort).should(never()).update(any());
    }

    @Test
    void 읽은_뒤_잠그기_전에_완료되면_실패를_기록하지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.COMPLETED, null, null)));

        // when
        sut.recordWalletRefundFailure(failed("INSUFFICIENT_BALANCE"));

        // then
        then(refundPersistencePort).should(never()).update(any());
    }

    private static WalletRefundCompletedCommand completed(Long orderId, long amount) {
        return new WalletRefundCompletedCommand(REFUND_ID, orderId, BUYER_ID, amount);
    }

    private static WalletRefundFailedCommand failed(String reason) {
        return new WalletRefundFailedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT, reason);
    }

    // 저장된 환불을 되살린다. 단가 10,000 x 2개, 배송비 0이라 돌려줄 금액은 20,000원
    private static Refund refund(RefundStatus status, RefundFailedStep failedStep, String failureCode) {
        return Refund.restore(REFUND_ID, ORDER_ID, 101L, 2, BUYER_ID, 5L, 55L, "감자", "1kg", 10_000L, 20_000L,
                0L, 0L, 0L, null, status, null, null, null, failedStep, failureCode, null, null);
    }
}
