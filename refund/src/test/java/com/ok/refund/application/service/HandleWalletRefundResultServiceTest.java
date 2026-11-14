package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.in.WalletRefundCompletedCommand;
import com.ok.refund.application.port.in.WalletRefundFailedCommand;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.StockRelease;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStepType;
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
    // Long 캐시(-128~127) 밖의 번호를 쓴다. 캐시 안 번호는 같은 값이면 같은 객체라, 주문 비교를 실수로 != 로 바꿔도 테스트가 통과한다
    private static final Long ORDER_ID = 1_000L;
    // 주문 번호와 같은 이유로 Long 캐시 밖의 번호를 쓴다(구매자 비교를 실수로 != 로 바꿔도 통과하지 않게)
    private static final Long BUYER_ID = 2_000L;
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
    @MethodSource("어긋난_주문_구매자_금액")
    void 완료_메시지의_금액이나_주문이나_구매자가_다르면_완료하지_않고_불일치를_기록한다(long orderId, long buyerId, long amount) {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));

        // when
        sut.completeAfterWalletRefund(completed(orderId, buyerId, amount));

        // then
        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().update(saved.capture());
        assertThat(saved.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getValue().getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(saved.getValue().getFailedStep()).isEqualTo(RefundStepType.WALLET);
            softly.assertThat(saved.getValue().getFailureCode()).isEqualTo("RESULT_MISMATCH");
        });
        then(productStockReleasePort).should(never()).releaseStock(any());
    }

    static Stream<Arguments> 어긋난_주문_구매자_금액() {
        return Stream.of(
                Arguments.of(ORDER_ID, BUYER_ID, AMOUNT - 1),
                Arguments.of(ORDER_ID, BUYER_ID, AMOUNT + 1),
                Arguments.of(1_001L, BUYER_ID, AMOUNT),
                Arguments.of(ORDER_ID, 2_001L, AMOUNT));
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
            softly.assertThat(saved.getValue().getFailedStep()).isEqualTo(RefundStepType.PRODUCT);
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
            softly.assertThat(saved.getValue().getFailedStep()).isEqualTo(RefundStepType.WALLET);
            softly.assertThat(saved.getValue().getFailureCode()).isEqualTo("INSUFFICIENT_BALANCE");
        });
    }

    @Test
    void 같은_실패가_이미_기록돼_있으면_넘긴다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING, RefundStepType.WALLET, "INSUFFICIENT_BALANCE")));

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
                refund(RefundStatus.PROCESSING, RefundStepType.WALLET, "INSUFFICIENT_BALANCE")));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING, RefundStepType.WALLET, "INSUFFICIENT_BALANCE")));

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

        // then: 읽기 단계에서 걸러 잠금 구간까지 가지 않는다.
        // 잠금 조회를 확인하는 이유: 스텁하지 않은 잠금 조회는 빈 값을 돌려줘, 읽기 단계 거르기가 빠져도 update는 안 불려 그것만으로는 못 잡는다
        then(refundPersistencePort).should(never()).findByIdForUpdate(any());
        then(refundPersistencePort).should(never()).update(any());
    }

    // 완료된 환불에 실패가 오면 중복이 아니라 앞선 완료 결과와 모순이다. WARN으로 남기되 상태는 그대로 둔다
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

    @ParameterizedTest
    @MethodSource("어긋난_주문_구매자_금액")
    void 실패_메시지의_금액이나_주문이나_구매자가_환불과_다르면_불일치를_기록한다(long orderId, long buyerId, long amount) {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));

        // when
        sut.recordWalletRefundFailure(failed(orderId, buyerId, amount, "INSUFFICIENT_BALANCE"));

        // then: 지갑 사유가 아니라 불일치로 남아야 어느 번호가 틀렸는지 사람이 확인한다
        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().update(saved.capture());
        assertThat(saved.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getValue().getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(saved.getValue().getFailedStep()).isEqualTo(RefundStepType.WALLET);
            softly.assertThat(saved.getValue().getFailureCode()).isEqualTo("RESULT_MISMATCH");
        });
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

    // ---- 재고 실패(PRODUCT)가 기록된 환불 ----
    // PRODUCT는 지갑 완료 뒤에만 남는다. 지갑 결과로 WALLET로 바꾸면 #546이 돈을 다시 가져오지 않고 주문만 되돌린다

    @Test
    void 재고_실패가_기록된_환불의_실패_메시지는_기록하지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING, RefundStepType.PRODUCT, "PRODUCT_API_FAILED")));

        // when
        sut.recordWalletRefundFailure(failed("INSUFFICIENT_BALANCE"));

        // then
        then(refundPersistencePort).should(never()).findByIdForUpdate(any());
        then(refundPersistencePort).should(never()).update(any());
    }

    @Test
    void 재고_실패가_기록된_환불에_주문이_다른_실패_메시지가_와도_불일치를_기록하지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING, RefundStepType.PRODUCT, "PRODUCT_API_FAILED")));

        // when
        sut.recordWalletRefundFailure(failed(1_001L, "INSUFFICIENT_BALANCE"));

        // then
        then(refundPersistencePort).should(never()).findByIdForUpdate(any());
        then(refundPersistencePort).should(never()).update(any());
    }

    @Test
    void 재고_실패가_기록된_환불에_금액이_다른_완료_메시지가_와도_불일치를_기록하지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING, RefundStepType.PRODUCT, "PRODUCT_API_FAILED")));

        // when
        sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT - 1));

        // then
        then(refundPersistencePort).should(never()).findByIdForUpdate(any());
        then(refundPersistencePort).should(never()).update(any());
        then(productStockReleasePort).should(never()).releaseStock(any());
    }

    @Test
    void 읽은_뒤_잠그기_전에_재고_실패가_기록되면_실패_메시지를_기록하지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING, RefundStepType.PRODUCT, "PRODUCT_API_FAILED")));

        // when
        sut.recordWalletRefundFailure(failed("INSUFFICIENT_BALANCE"));

        // then
        then(refundPersistencePort).should(never()).update(any());
    }

    @Test
    void 읽은_뒤_잠그기_전에_재고_실패가_기록되면_불일치를_기록하지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING, null, null)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING, RefundStepType.PRODUCT, "PRODUCT_API_FAILED")));

        // when
        sut.recordWalletRefundFailure(failed(1_001L, "INSUFFICIENT_BALANCE"));

        // then
        then(refundPersistencePort).should(never()).update(any());
    }

    // orderId를 long으로 받아 명령을 만들 때 새 Long 객체가 되게 한다. 환불(ORDER_ID 상수 객체)과 값은 같아도 객체가 달라야
    // 주문 비교가 값(equals)이 아니라 객체(!=)를 보면 테스트가 실패한다(운영에서는 DB와 메시지에서 따로 읽어 늘 다른 객체다)
    private static WalletRefundCompletedCommand completed(long orderId, long amount) {
        return completed(orderId, BUYER_ID, amount);
    }

    // buyerId도 long으로 받아 새 Long 객체가 되게 한다(구매자 비교가 equals인지 보려는 것, 주문과 같은 이유)
    private static WalletRefundCompletedCommand completed(long orderId, long buyerId, long amount) {
        return new WalletRefundCompletedCommand(REFUND_ID, orderId, buyerId, amount);
    }

    private static WalletRefundFailedCommand failed(String reason) {
        return failed(ORDER_ID, reason);
    }

    // long으로 받아 새 Long 객체가 되게 한다(주문 비교가 equals인지 보려는 것, completed와 같은 이유)
    private static WalletRefundFailedCommand failed(long orderId, String reason) {
        return failed(orderId, BUYER_ID, AMOUNT, reason);
    }

    private static WalletRefundFailedCommand failed(long orderId, long buyerId, long amount, String reason) {
        return new WalletRefundFailedCommand(REFUND_ID, orderId, buyerId, amount, reason);
    }

    // 저장된 환불을 되살린다. 단가 10,000 x 2개, 배송비 0이라 돌려줄 금액은 20,000원
    private static Refund refund(RefundStatus status, RefundStepType failedStep, String failureCode) {
        return Refund.restore(REFUND_ID, ORDER_ID, 101L, 2, BUYER_ID, 5L, 55L, "감자", "1kg", 10_000L, 20_000L,
                0L, 0L, 0L, null, status, null, null, null, failedStep, failureCode, null, null);
    }
}
