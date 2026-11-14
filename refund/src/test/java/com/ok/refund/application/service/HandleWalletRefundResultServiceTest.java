package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.in.WalletRefundCompletedCommand;
import com.ok.refund.application.port.in.WalletRefundFailedCommand;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.application.port.out.StockRelease;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepStatus;
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
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

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
    // 단계 줄을 스텁하지 않은 테스트의 환불은 단계 줄이 없다(#574 전에 승인된 환불, 비어 있는 Optional을 돌려주는 기본 동작)
    @Mock
    RefundStepPersistencePort refundStepPersistencePort;

    HandleWalletRefundResultService sut;

    @BeforeEach
    void setUp() {
        NoOpTransactionManager transactionManager = new NoOpTransactionManager();
        sut = new HandleWalletRefundResultService(refundPersistencePort, refundStepPersistencePort, productStockReleasePort,
                new RefundStepRunner(refundPersistencePort, refundStepPersistencePort, CLOCK, transactionManager), CLOCK, transactionManager);
    }

    // ---- 완료 ----

    @Test
    void 완료_메시지가_금액과_주문이_같으면_재고를_되돌리고_완료로_바꾼다() {
        // given: 지갑 줄은 결과 대기. 재고 줄은 먼저 읽을 때와 시작 구간에서는 없고, 완료 구간에서는 시작 구간이 만든 줄이 보인다
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        RefundStep walletStep = step(RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT, 1, null);
        RefundStep startedProductStep = step(RefundStepType.PRODUCT, RefundStepStatus.IN_PROGRESS, 1, null);
        givenSteps(walletStep, null, null, startedProductStep);

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
        ArgumentCaptor<RefundStep> created = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().saveNew(created.capture());
        assertThat(created.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(created.getValue().getType()).isEqualTo(RefundStepType.PRODUCT);
            softly.assertThat(created.getValue().getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
        });
        ArgumentCaptor<RefundStep> updated = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should(times(2)).update(updated.capture());
        assertThat(updated.getAllValues()).containsExactly(walletStep, startedProductStep);
        assertSoftly(softly -> {
            softly.assertThat(walletStep.getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
            softly.assertThat(startedProductStep.getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
        });
    }

    @Test
    void 단계_줄이_없는_처리_중_환불에_완료가_오면_지갑_줄을_성공으로_만들고_완료한다() {
        // given: #574 전에 승인돼 단계 줄이 하나도 없는 환불(스텁하지 않아 모든 조회가 빈 값)
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));

        // when
        sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT));

        // then
        ArgumentCaptor<RefundStep> created = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should(times(2)).saveNew(created.capture());
        RefundStep wallet = created.getAllValues().get(0);
        RefundStep product = created.getAllValues().get(1);
        assertSoftly(softly -> {
            softly.assertThat(wallet.getType()).isEqualTo(RefundStepType.WALLET);
            softly.assertThat(wallet.getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
            softly.assertThat(product.getType()).isEqualTo(RefundStepType.PRODUCT);
            softly.assertThat(product.getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
        });
        then(productStockReleasePort).should().releaseStock(any());
        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().update(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RefundStatus.COMPLETED);
    }

    // 지갑이 실패나 어긋난 결과를 먼저 알린 뒤 올바른 완료를 보내는 경우다. 앞선 기록은 멈춤으로 남기고(#544 결정 8) 환불은 완료한다
    @Test
    void 불일치_뒤_올바른_완료가_오면_지갑_줄은_멈춤으로_남고_환불은_완료된다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING)));
        RefundStep walletStep = step(RefundStepType.WALLET, RefundStepStatus.STOPPED, 1, "RESULT_MISMATCH");
        RefundStep startedProductStep = step(RefundStepType.PRODUCT, RefundStepStatus.IN_PROGRESS, 1, null);
        givenSteps(walletStep, null, null, startedProductStep);

        // when
        sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT));

        // then: 지갑 줄은 저장하지 않고(멈춤 그대로) 재고 줄만 만들고 성공으로 바꾼다
        ArgumentCaptor<RefundStep> updated = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().update(updated.capture());
        assertThat(updated.getValue()).isSameAs(startedProductStep);
        assertSoftly(softly -> {
            softly.assertThat(walletStep.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(walletStep.getLastReason()).isEqualTo("RESULT_MISMATCH");
            softly.assertThat(startedProductStep.getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
        });
        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().update(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RefundStatus.COMPLETED);
    }

    @Test
    void 재고_실패_뒤_같은_완료가_다시_오면_재고_단계를_다시_시작한다() {
        // given: 앞선 완료에서 지갑 줄은 성공, 재고 줄은 상품 호출 실패로 다시 시도 대기
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING)));
        RefundStep walletStep = step(RefundStepType.WALLET, RefundStepStatus.SUCCEEDED, 1, null);
        RefundStep productStep = step(RefundStepType.PRODUCT, RefundStepStatus.READY, 1, "PRODUCT_API_FAILED");
        givenSteps(walletStep, productStep);
        // 저장된 객체는 뒤 구간에서 다시 바뀌므로, 상품을 부르는 순간의 재고 줄 상태를 따로 담아 둔다
        RefundStepStatus[] productStatusAtCall = new RefundStepStatus[1];
        willAnswer(invocation -> {
            productStatusAtCall[0] = productStep.getStatus();
            return null;
        }).given(productStockReleasePort).releaseStock(any());

        // when
        sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT));

        // then: 재고 줄은 상품을 부르기 전에 시도 중(두 번째 시도)으로 저장되고, 완료와 함께 성공이 된다. 새 줄은 만들지 않는다
        assertThat(productStatusAtCall[0]).isEqualTo(RefundStepStatus.IN_PROGRESS);
        ArgumentCaptor<RefundStep> updated = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should(times(2)).update(updated.capture());
        assertThat(updated.getAllValues()).containsOnly(productStep);
        assertSoftly(softly -> {
            softly.assertThat(productStep.getAttemptCount()).isEqualTo(2);
            softly.assertThat(productStep.getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
            softly.assertThat(walletStep.getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
        });
        then(refundStepPersistencePort).should(never()).saveNew(any());
        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().update(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(RefundStatus.COMPLETED);
    }

    @Test
    void 이미_완료된_환불의_완료_메시지는_넘기고_재고를_다시_부르지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.COMPLETED)));

        // when
        sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT));

        // then
        then(productStockReleasePort).should(never()).releaseStock(any());
        then(refundPersistencePort).should(never()).update(any());
    }

    @Test
    void 읽은_뒤_잠그기_전에_완료되면_완료로_다시_바꾸지_않는다() {
        // given: 먼저 읽은 뒤 같은 메시지가 먼저 완료한 경우
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.COMPLETED)));

        // when
        sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT));

        // then: 재고 단계를 시작하는 구간에서 걸러 단계 줄도, 상품 호출도 없다
        then(refundPersistencePort).should(never()).update(any());
        then(refundStepPersistencePort).should(never()).saveNew(any());
        then(productStockReleasePort).should(never()).releaseStock(any());
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
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(status)));

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
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));

        // when
        sut.completeAfterWalletRefund(completed(orderId, buyerId, amount));

        // then: 지갑 줄이 없던 환불이라 멈춤으로 만들어 남긴다. 환불 행은 처리 중 그대로라 저장하지 않는다
        then(refundPersistencePort).should(never()).update(any());
        ArgumentCaptor<RefundStep> created = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().saveNew(created.capture());
        assertThat(created.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(created.getValue().getType()).isEqualTo(RefundStepType.WALLET);
            softly.assertThat(created.getValue().getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(created.getValue().getLastReason()).isEqualTo("RESULT_MISMATCH");
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
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        // 재고 줄은 먼저 읽을 때와 시작 구간에서는 없고, 실패를 기록할 때는 시작 구간이 만든 줄이 보인다
        RefundStep walletStep = step(RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT, 1, null);
        RefundStep startedProductStep = step(RefundStepType.PRODUCT, RefundStepStatus.IN_PROGRESS, 1, null);
        givenSteps(walletStep, null, null, startedProductStep);
        willThrow(new RestApiException(RefundErrorCode.PRODUCT_API_FAILED)).given(productStockReleasePort).releaseStock(any());

        // when & then
        assertThatThrownBy(() -> sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.PRODUCT_API_FAILED));
        // 실패는 재고 줄에만 남고 환불 행은 처리 중 그대로라 저장하지 않는다
        then(refundPersistencePort).should(never()).update(any());
        ArgumentCaptor<RefundStep> updated = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should(times(2)).update(updated.capture());
        assertThat(updated.getAllValues()).containsExactly(walletStep, startedProductStep);
        assertSoftly(softly -> {
            softly.assertThat(startedProductStep.getStatus()).isEqualTo(RefundStepStatus.READY);
            softly.assertThat(startedProductStep.getLastReason()).isEqualTo("PRODUCT_API_FAILED");
        });
    }

    // ---- 실패 ----

    @Test
    void 실패_메시지는_WALLET_단계와_이유를_기록하고_처리_중으로_둔다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        RefundStep walletStep = step(RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT, 1, null);
        givenSteps(walletStep, null);

        // when
        sut.recordWalletRefundFailure(failed("INSUFFICIENT_BALANCE"));

        // then: 실패는 지갑 줄에만 남고 환불 행은 처리 중 그대로라 저장하지 않는다
        then(refundPersistencePort).should(never()).update(any());
        ArgumentCaptor<RefundStep> updated = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().update(updated.capture());
        assertThat(updated.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(updated.getValue().getType()).isEqualTo(RefundStepType.WALLET);
            softly.assertThat(updated.getValue().getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(updated.getValue().getLastReason()).isEqualTo("INSUFFICIENT_BALANCE");
        });
    }

    @Test
    void 단계_줄이_없는_처리_중_환불에_실패가_오면_지갑_줄을_멈춤으로_만든다() {
        // given: #574 전에 승인돼 단계 줄이 하나도 없는 환불(스텁하지 않아 모든 조회가 빈 값)
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));

        // when
        sut.recordWalletRefundFailure(failed("INSUFFICIENT_BALANCE"));

        // then
        ArgumentCaptor<RefundStep> created = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().saveNew(created.capture());
        assertThat(created.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(created.getValue().getRefundId()).isEqualTo(REFUND_ID);
            softly.assertThat(created.getValue().getType()).isEqualTo(RefundStepType.WALLET);
            softly.assertThat(created.getValue().getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(created.getValue().getLastReason()).isEqualTo("INSUFFICIENT_BALANCE");
            softly.assertThat(created.getValue().getNextCheckAt()).isNull();
        });
    }

    @Test
    void 같은_실패가_이미_기록돼_있으면_넘긴다() {
        // given: 같은 이유로 멈춘 지갑 줄
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING)));
        givenSteps(step(RefundStepType.WALLET, RefundStepStatus.STOPPED, 1, "INSUFFICIENT_BALANCE"), null);

        // when
        sut.recordWalletRefundFailure(failed("INSUFFICIENT_BALANCE"));

        // then
        then(refundPersistencePort).should(never()).findByIdForUpdate(any());
        then(refundPersistencePort).should(never()).update(any());
        then(refundStepPersistencePort).should(never()).update(any());
    }

    @Test
    void 다른_이유의_실패는_마지막_실패로_덮어쓴다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING)));
        givenSteps(step(RefundStepType.WALLET, RefundStepStatus.STOPPED, 1, "INSUFFICIENT_BALANCE"), null);

        // when
        sut.recordWalletRefundFailure(failed("WALLET_FROZEN"));

        // then
        then(refundPersistencePort).should(never()).update(any());
        ArgumentCaptor<RefundStep> updated = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().update(updated.capture());
        assertThat(updated.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(updated.getValue().getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(updated.getValue().getLastReason()).isEqualTo("WALLET_FROZEN");
        });
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
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(status)));

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
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.COMPLETED)));

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
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        givenSteps(step(RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT, 1, null), null);

        // when
        sut.recordWalletRefundFailure(failed(orderId, buyerId, amount, "INSUFFICIENT_BALANCE"));

        // then: 지갑 사유가 아니라 불일치로 남아야 어느 번호가 틀렸는지 사람이 확인한다. 환불 행은 저장하지 않는다
        then(refundPersistencePort).should(never()).update(any());
        ArgumentCaptor<RefundStep> updated = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().update(updated.capture());
        assertThat(updated.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(updated.getValue().getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(updated.getValue().getLastReason()).isEqualTo("RESULT_MISMATCH");
        });
    }

    @Test
    void 읽은_뒤_잠그기_전에_완료되면_실패를_기록하지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.COMPLETED)));

        // when
        sut.recordWalletRefundFailure(failed("INSUFFICIENT_BALANCE"));

        // then
        then(refundPersistencePort).should(never()).update(any());
    }

    // ---- 재고 단계가 시작된 환불(재고 실패 뒤 다시 시도 대기) ----
    // 재고 줄은 지갑 완료를 반영할 때만 만든다. 지갑 결과로 지갑 실패를 남기면 #546이 돈을 다시 가져오지 않고 주문만 되돌린다

    @Test
    void 재고_실패가_기록된_환불의_실패_메시지는_기록하지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING)));
        givenSteps(succeededWalletStep(), failedProductStep());

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
                refund(RefundStatus.PROCESSING)));
        givenSteps(succeededWalletStep(), failedProductStep());

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
                refund(RefundStatus.PROCESSING)));
        givenSteps(succeededWalletStep(), failedProductStep());

        // when
        sut.completeAfterWalletRefund(completed(ORDER_ID, AMOUNT - 1));

        // then
        then(refundPersistencePort).should(never()).findByIdForUpdate(any());
        then(refundPersistencePort).should(never()).update(any());
        then(productStockReleasePort).should(never()).releaseStock(any());
    }

    @Test
    void 읽은_뒤_잠그기_전에_재고_실패가_기록되면_실패_메시지를_기록하지_않는다() {
        // given: 먼저 읽을 때는 재고 줄이 없고, 잠근 뒤에는 있다
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING)));
        givenSteps(succeededWalletStep(), null, failedProductStep());

        // when
        sut.recordWalletRefundFailure(failed("INSUFFICIENT_BALANCE"));

        // then
        then(refundPersistencePort).should(never()).update(any());
        then(refundStepPersistencePort).should(never()).update(any());
        then(refundStepPersistencePort).should(never()).saveNew(any());
    }

    @Test
    void 읽은_뒤_잠그기_전에_재고_실패가_기록되면_불일치를_기록하지_않는다() {
        // given: 먼저 읽을 때는 재고 줄이 없고, 잠근 뒤에는 있다
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(
                refund(RefundStatus.PROCESSING)));
        givenSteps(succeededWalletStep(), null, failedProductStep());

        // when
        sut.recordWalletRefundFailure(failed(1_001L, "INSUFFICIENT_BALANCE"));

        // then
        then(refundPersistencePort).should(never()).update(any());
        then(refundStepPersistencePort).should(never()).update(any());
        then(refundStepPersistencePort).should(never()).saveNew(any());
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

    // 지갑 줄과 재고 줄 조회를 함께 스텁한다. 한쪽만 스텁하면 다른 쪽 조회가 "다른 인자 호출"로 엄격 스텁 검사에 걸린다.
    // 재고 줄은 부를 때마다 차례로 돌려준다(null은 줄 없음). 마지막 값은 그 뒤 조회에도 계속 쓰인다
    private void givenSteps(RefundStep walletStep, RefundStep firstProductStep, RefundStep... nextProductSteps) {
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET))
                .willReturn(Optional.ofNullable(walletStep));
        @SuppressWarnings("unchecked")
        Optional<RefundStep>[] next = Arrays.stream(nextProductSteps).map(Optional::ofNullable).toArray(Optional[]::new);
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.PRODUCT))
                .willReturn(Optional.ofNullable(firstProductStep), next);
    }

    // 저장된 단계 줄을 되살린다. 끝난 줄과 멈춘 줄은 다시 볼 시각이 없다
    private static RefundStep step(RefundStepType type, RefundStepStatus status, int attemptCount, String lastReason) {
        LocalDateTime nextCheckAt = status.isFinished() ? null : NOW.plusMinutes(1);
        return RefundStep.restore(type.ordinal() + 1L, REFUND_ID, type, status, attemptCount, nextCheckAt, lastReason, NOW);
    }

    private static RefundStep succeededWalletStep() {
        return step(RefundStepType.WALLET, RefundStepStatus.SUCCEEDED, 1, null);
    }

    // 지갑 완료 뒤 상품 호출이 실패해 다시 시도를 기다리는 재고 줄
    private static RefundStep failedProductStep() {
        return step(RefundStepType.PRODUCT, RefundStepStatus.READY, 1, "PRODUCT_API_FAILED");
    }

    // 저장된 환불을 되살린다. 단가 10,000 x 2개, 배송비 0이라 돌려줄 금액은 20,000원
    private static Refund refund(RefundStatus status) {
        return Refund.restore(REFUND_ID, ORDER_ID, 101L, 2, BUYER_ID, 5L, 55L, "감자", "1kg", 10_000L, 20_000L,
                0L, 0L, 0L, null, status, null, null, null, null, null);
    }
}
