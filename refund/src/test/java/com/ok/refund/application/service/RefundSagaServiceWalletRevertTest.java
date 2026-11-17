package com.ok.refund.application.service;

import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.in.WalletRevertCompletedCommand;
import com.ok.refund.application.port.in.WalletRevertFailedCommand;
import com.ok.refund.application.port.out.OrderCancellationPort;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.application.port.out.WalletRefundRequestPort;
import com.ok.refund.application.port.out.WalletRevertRequest;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepStatus;
import com.ok.refund.domain.RefundStepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

// 회수(#546): 예시는 환불 7번. 주문 1000번, 구매자 2000번에게 20,000원을 돌려줬는데 상품이 재고 해제를 거절해 그 돈을 다시 가져온다.
// 회수 요청 발행(알람이 깨운 다시 진행)과 지갑이 보낸 회수 결과(다시 가져옴·실패)를 본다
@ExtendWith(MockitoExtension.class)
class RefundSagaServiceWalletRevertTest {

    private static final Long REFUND_ID = 7L;
    private static final Long ORDER_ID = 1_000L;
    private static final Long BUYER_ID = 2_000L;
    private static final long AMOUNT = 20_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 10, 0);

    @Mock
    RefundPersistencePort refundPersistencePort;
    @Mock
    RefundStepPersistencePort refundStepPersistencePort;
    @Mock
    OrderCancellationPort orderCancellationPort;
    @Mock
    WalletRefundRequestPort walletRefundRequestPort;
    @Mock
    ProductStockReleasePort productStockReleasePort;

    RefundSagaService saga;

    @BeforeEach
    void setUp() {
        saga = new RefundSagaService(orderCancellationPort, walletRefundRequestPort, productStockReleasePort,
                refundPersistencePort, refundStepPersistencePort, CLOCK, new NoOpTransactionManager());
    }

    // ---- 회수 요청 발행 (알람이 깨운 다시 진행) ----

    // 처음 바로 보내기가 실패해 회수 줄이 할 차례로 남았다(첫 시도 1번 씀). 알람이 다시 집어 보낸다
    @Test
    void 회수_줄이_할_차례면_알람이_환불_값으로_회수를_다시_요청하고_결과를_기다린다() {
        // given
        givenResumable(RefundStep.restore(4L, REFUND_ID, RefundStepType.WALLET_REVERT, RefundStepStatus.READY,
                1, NOW.minusMinutes(1), "IllegalStateException", NOW.minusMinutes(2)));

        // when
        RefundResult sut = saga.resume(REFUND_ID);

        // then
        assertThat(sut.status()).isEqualTo("PROCESSING");
        then(walletRefundRequestPort).should().requestRevert(new WalletRevertRequest(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT));
        then(walletRefundRequestPort).should(never()).requestRefund(any());
        ArgumentCaptor<RefundStep> captor = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should(atLeastOnce()).update(captor.capture());
        // 같은 줄 객체를 시작 → 결과 대기로 차례로 바꾸므로 마지막 모습을 본다
        RefundStep revert = captor.getAllValues().getLast();
        assertThat(revert).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(revert.getType()).isEqualTo(RefundStepType.WALLET_REVERT);
            softly.assertThat(revert.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(revert.getAttemptCount()).isEqualTo(2);
            softly.assertThat(revert.getAwaitingSince()).isEqualTo(NOW);
        });
    }

    // 회수 결과를 5분 기다려도 오지 않으면 같은 refundId로 다시 보낸다(#577 규칙). 지갑은 refundId로 한 번만 가져온다
    @Test
    void 회수_결과를_기다리는_시각이_지나면_같은_회수를_다시_요청한다() {
        // given
        givenResumable(RefundStep.restore(4L, REFUND_ID, RefundStepType.WALLET_REVERT, RefundStepStatus.AWAITING_RESULT,
                1, NOW, null, NOW.minusMinutes(5), NOW.minusMinutes(6)));

        // when
        saga.resume(REFUND_ID);

        // then
        then(walletRefundRequestPort).should().requestRevert(new WalletRevertRequest(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT));
    }

    // 처음 회수 결과를 기다린 지 24시간이 지났다(RefundStep.RESEND_LIMIT). 더 보내지 않고 늦은 결과만 기다린다
    @Test
    void 회수_결과를_처음_기다린_지_24시간이_지나면_다시_요청하지_않고_늦은_결과만_기다린다() {
        // given
        givenResumable(RefundStep.restore(4L, REFUND_ID, RefundStepType.WALLET_REVERT, RefundStepStatus.AWAITING_RESULT,
                1, NOW, null, NOW.minusHours(24), NOW.minusHours(25)));

        // when
        saga.resume(REFUND_ID);

        // then
        then(walletRefundRequestPort).should(never()).requestRevert(any());
        RefundStep held = updatedStep();
        assertThat(held).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(held.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(held.getNextCheckAt()).isNull();
        });
    }

    // ---- 지갑이 보낸 회수 결과 ----

    @Test
    void 지갑이_다시_가져오면_회수_줄을_성공으로_남기고_환불은_처리_중으로_둔다() {
        // given
        givenLockedWithRevert(awaitingRevert());

        // when
        saga.recordWalletRevertCompleted(new WalletRevertCompletedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT));

        // then
        RefundStep revert = updatedStep();
        assertThat(revert.getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
        then(refundPersistencePort).should(never()).update(any());
    }

    @Test
    void 지갑이_다시_가져오지_못하면_지갑이_알려_준_이유로_회수_줄을_멈춘다() {
        // given
        givenLockedWithRevert(awaitingRevert());

        // when
        saga.recordWalletRevertFailure(new WalletRevertFailedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT, "INSUFFICIENT_BALANCE"));

        // then
        RefundStep revert = updatedStep();
        assertThat(revert).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(revert.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(revert.getLastReason()).isEqualTo("INSUFFICIENT_BALANCE");
        });
        then(refundPersistencePort).should(never()).update(any());
    }

    // 지갑 반환 결과와 같은 규칙: 금액·주문·구매자가 다르면 불일치로 멈춘다(돈이 어긋난 채 성공으로 두지 않고 사람이 본다)
    @Test
    void 회수_결과의_금액이_환불과_다르면_회수_줄을_불일치로_멈춘다() {
        // given
        givenLockedWithRevert(awaitingRevert());

        // when
        saga.recordWalletRevertCompleted(new WalletRevertCompletedCommand(REFUND_ID, ORDER_ID, BUYER_ID, 19_000L));

        // then
        RefundStep revert = updatedStep();
        assertThat(revert).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(revert.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(revert.getLastReason()).isEqualTo(RefundSagaService.RESULT_MISMATCH);
        });
    }

    // 지갑은 같은 refundId에 첫 결과를 다시 보낸다. 끝난 회수 줄은 바꾸지 않는다
    @Test
    void 회수_줄이_이미_끝났으면_같은_결과가_다시_와도_쓰지_않는다() {
        // given
        givenLockedWithRevert(RefundStep.restore(4L, REFUND_ID, RefundStepType.WALLET_REVERT, RefundStepStatus.SUCCEEDED,
                1, null, null, NOW.minusMinutes(3), NOW.minusMinutes(5)));

        // when
        saga.recordWalletRevertCompleted(new WalletRevertCompletedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT));

        // then
        then(refundStepPersistencePort).should(never()).update(any());
    }

    @Test
    void 회수_줄이_없는_환불의_회수_결과는_쓰지_않고_넘긴다() {
        // given
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));

        // when
        saga.recordWalletRevertCompleted(new WalletRevertCompletedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT));

        // then
        then(refundStepPersistencePort).should(never()).update(any());
        then(refundStepPersistencePort).should(never()).saveNew(any());
    }

    // 지갑 반환을 끝낸 뒤 재고가 거절로 멈춘 환불의 단계 줄에 회수 줄을 더해 다시 진행할 수 있게 한다
    private void givenResumable(RefundStep revert) {
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundStepPersistencePort.findAllByRefundId(REFUND_ID)).willReturn(List.of(
                step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED),
                step(RefundStepType.WALLET, RefundStepStatus.SUCCEEDED),
                RefundStep.restore(3L, REFUND_ID, RefundStepType.PRODUCT, RefundStepStatus.STOPPED, 1, null,
                        "PRODUCT_RELEASE_REFUSED", NOW.minusMinutes(2)),
                revert));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET_REVERT)).willReturn(Optional.of(revert));
    }

    private void givenLockedWithRevert(RefundStep revert) {
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET_REVERT)).willReturn(Optional.of(revert));
    }

    private RefundStep updatedStep() {
        ArgumentCaptor<RefundStep> captor = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().update(captor.capture());
        return captor.getValue();
    }

    private static RefundStep awaitingRevert() {
        return RefundStep.restore(4L, REFUND_ID, RefundStepType.WALLET_REVERT, RefundStepStatus.AWAITING_RESULT,
                1, NOW.plusMinutes(5), null, NOW, NOW.minusMinutes(1));
    }

    private static Refund processing() {
        return Refund.restore(REFUND_ID, ORDER_ID, 101L, 2, BUYER_ID, 5L, 55L, "감자", "1kg", 10_000L, AMOUNT,
                0L, 0L, 0L, null, RefundStatus.PROCESSING, null, null, null, null, null);
    }

    private static RefundStep step(RefundStepType type, RefundStepStatus status) {
        LocalDateTime nextCheckAt = status.isFinished() ? null : NOW.plusMinutes(1);
        return RefundStep.restore(type.ordinal() + 1L, REFUND_ID, type, status, 1, nextCheckAt, null, NOW);
    }
}
