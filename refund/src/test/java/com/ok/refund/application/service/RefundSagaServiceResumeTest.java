package com.ok.refund.application.service;

import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.out.OrderCancellationPort;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.application.port.out.WalletRefundRequestPort;
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

// 환불 번호 입구(resume)가 단계 줄을 읽은 뒤 잠그기 전에 다른 경로가 먼저 움직인 경우를 본다.
// 알람 서버 두 대가 같은 환불을 동시에 깨워도 같은 반환 요청이 두 번 나가지 않아야 한다(#616 결정 14·15)
@ExtendWith(MockitoExtension.class)
class RefundSagaServiceResumeTest {

    private static final Long REFUND_ID = 7L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 15, 10, 0);

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

    @Test
    void 읽은_뒤_다른_쪽이_지갑_단계를_먼저_시작했으면_보내지_않는다() {
        // given: 읽을 때는 지갑 할 차례, 잠근 뒤에는 다른 알람이 이미 시도 중으로 바꿈
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findAllByRefundId(REFUND_ID))
                .willReturn(List.of(step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED), step(RefundStepType.WALLET, RefundStepStatus.READY)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET))
                .willReturn(Optional.of(step(RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS)));

        // when
        RefundResult sut = saga.resume(REFUND_ID);

        // then
        assertThat(sut.status()).isEqualTo("PROCESSING");
        then(walletRefundRequestPort).should(never()).requestRefund(any());
        then(refundStepPersistencePort).should(never()).update(any());
    }

    @Test
    void 읽은_뒤_환불이_끝났으면_부르지_않고_지금_상태를_돌려준다() {
        // given: 읽을 때는 처리 중, 잠근 뒤에는 이미 완료
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findAllByRefundId(REFUND_ID))
                .willReturn(List.of(step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED), step(RefundStepType.WALLET, RefundStepStatus.READY)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.COMPLETED)));

        // when
        RefundResult sut = saga.resume(REFUND_ID);

        // then
        assertThat(sut.status()).isEqualTo("COMPLETED");
        then(walletRefundRequestPort).should(never()).requestRefund(any());
        then(refundStepPersistencePort).should(never()).update(any());
        then(refundStepPersistencePort).should(never()).saveNew(any());
    }

    // 지갑 줄이 시도 중인 채로 만료됐다(시도하던 서버가 죽음). 잠근 뒤에도 만료 그대로면 다시 집어 한 번 보낸다(#576)
    @Test
    void 시도_중인_채로_만료된_지갑_줄은_다시_집어_보낸다() {
        // given: 만료 시각이 지금(10:00)인 시도 중 지갑 줄
        RefundStep expiredWallet = RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS,
                1, NOW, null, NOW.minusMinutes(2));
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findAllByRefundId(REFUND_ID))
                .willReturn(List.of(step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED), expiredWallet));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET))
                .willReturn(Optional.of(RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS,
                        1, NOW, null, NOW.minusMinutes(2))));

        // when
        RefundResult sut = saga.resume(REFUND_ID);

        // then
        assertThat(sut.status()).isEqualTo("PROCESSING");
        then(walletRefundRequestPort).should().requestRefund(any());
        ArgumentCaptor<RefundStep> captor = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should(atLeastOnce()).update(captor.capture());
        // 같은 줄 객체를 다시 집기 → 결과 대기로 차례로 바꾸므로 마지막 모습을 본다. 다시 집으며 시도 횟수가 하나 늘었다
        RefundStep restarted = captor.getAllValues().getLast();
        assertThat(restarted).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(restarted.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(restarted.getAttemptCount()).isEqualTo(2);
        });
    }

    // 시도 중 만료가 다섯 번째였다(횟수를 다 씀). 다시 집지 않고 멈춤으로 남기며 보내지 않는다(#576)
    @Test
    void 만료된_지갑_줄이_횟수를_다_썼으면_보내지_않고_멈춘다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findAllByRefundId(REFUND_ID)).willReturn(List.of(
                step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED),
                RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS, 5, NOW, null, NOW.minusMinutes(10))));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET))
                .willReturn(Optional.of(RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS,
                        5, NOW, null, NOW.minusMinutes(10))));

        // when
        RefundResult sut = saga.resume(REFUND_ID);

        // then
        assertThat(sut.status()).isEqualTo("PROCESSING");
        then(walletRefundRequestPort).should(never()).requestRefund(any());
        ArgumentCaptor<RefundStep> captor = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().update(captor.capture());
        RefundStep stopped = captor.getValue();
        assertThat(stopped).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(stopped.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(stopped.getLastReason()).isEqualTo(RefundStep.IN_PROGRESS_EXPIRED);
        });
    }

    // 읽을 때는 만료였지만 잠근 뒤 보니 다른 서버가 먼저 다시 집어 만료 시각을 새로 잡았다. 같은 요청을 두 번 보내지 않는다
    @Test
    void 읽은_뒤_다른_서버가_먼저_다시_집었으면_보내지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findAllByRefundId(REFUND_ID)).willReturn(List.of(
                step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED),
                RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS, 1, NOW, null, NOW.minusMinutes(2))));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET))
                .willReturn(Optional.of(RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS,
                        2, NOW.plusMinutes(2), null, NOW.minusMinutes(2))));

        // when
        RefundResult sut = saga.resume(REFUND_ID);

        // then
        assertThat(sut.status()).isEqualTo("PROCESSING");
        then(walletRefundRequestPort).should(never()).requestRefund(any());
        then(refundStepPersistencePort).should(never()).update(any());
    }

    // 주문 줄은 멈췄는데(만료가 반복돼 횟수를 다 씀) 늦게 깨어난 서버가 주문 성공을 보고 지갑·재고로 진행해, 재고 줄이 할 차례로 남았다.
    // 계획은 주문 줄에서 멈춤을 정한다. 재고 줄을 그대로 두면 시각이 바뀌지 않아 알람 매 회차 맨 앞에 다시 뽑히므로 멈춤으로 바꾼다(PR #628 검수)
    @Test
    void 다시_진행할_수_없는_환불의_남은_할_차례_줄은_멈춤으로_바꾼다() {
        // given
        RefundStep product = step(RefundStepType.PRODUCT, RefundStepStatus.READY);
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findAllByRefundId(REFUND_ID)).willReturn(List.of(
                step(RefundStepType.ORDER, RefundStepStatus.STOPPED),
                step(RefundStepType.WALLET, RefundStepStatus.SUCCEEDED),
                product));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));

        // when
        RefundResult sut = saga.resume(REFUND_ID);

        // then
        assertThat(sut.status()).isEqualTo("PROCESSING");
        then(productStockReleasePort).should(never()).releaseStock(any());
        ArgumentCaptor<RefundStep> captor = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().update(captor.capture());
        RefundStep stopped = captor.getValue();
        assertThat(stopped).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(stopped.getType()).isEqualTo(RefundStepType.PRODUCT);
            softly.assertThat(stopped.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(stopped.getLastReason()).isEqualTo(RefundSagaService.BLOCKED_BY_STOPPED_STEP);
        });
    }

    // 잠근 뒤 처리 중이 아니면 줄에 쓰지 않는다(#616 결정 15)
    @Test
    void 다시_진행할_수_없는데_잠근_뒤_처리_중이_아니면_줄을_바꾸지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findAllByRefundId(REFUND_ID)).willReturn(List.of(
                step(RefundStepType.ORDER, RefundStepStatus.STOPPED),
                step(RefundStepType.WALLET, RefundStepStatus.SUCCEEDED),
                step(RefundStepType.PRODUCT, RefundStepStatus.READY)));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.COMPLETED)));

        // when
        saga.resume(REFUND_ID);

        // then
        then(refundStepPersistencePort).should(never()).update(any());
    }

    // 지갑 결과를 기다리는 시각이 지났다(결과 메시지가 늦거나 버려짐). 잠근 뒤에도 그대로면 같은 요청을 한 번 다시 보낸다(#577).
    // 시도 횟수는 그대로이고, 보낸 뒤 다시 결과 대기(5분 뒤)로 돌아간다
    @Test
    void 결과를_기다리는_시각이_지난_지갑_줄은_같은_요청을_다시_보낸다() {
        // given: 기다리는 시각이 지금(10:00)인 결과 대기 지갑 줄, 시도 횟수 3
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findAllByRefundId(REFUND_ID))
                .willReturn(List.of(step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED), overdueAwaitingWallet()));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET))
                .willReturn(Optional.of(overdueAwaitingWallet()));

        // when
        RefundResult sut = saga.resume(REFUND_ID);

        // then
        assertThat(sut.status()).isEqualTo("PROCESSING");
        then(walletRefundRequestPort).should().requestRefund(any());
        ArgumentCaptor<RefundStep> captor = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should(atLeastOnce()).update(captor.capture());
        RefundStep resent = captor.getAllValues().getLast();
        assertThat(resent).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(resent.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(resent.getAttemptCount()).isEqualTo(3);
            softly.assertThat(resent.getNextCheckAt()).isEqualTo(NOW.plusMinutes(5));
        });
    }

    // 읽을 때는 기다리는 시각이 지났지만, 잠그기 전에 늦게 온 지갑 완료가 먼저 지갑 줄을 성공으로 바꿨다. 다시 보내지 않는다(#577)
    @Test
    void 읽은_뒤_늦은_지갑_완료가_먼저_반영됐으면_다시_보내지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findAllByRefundId(REFUND_ID))
                .willReturn(List.of(step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED), overdueAwaitingWallet()));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET))
                .willReturn(Optional.of(step(RefundStepType.WALLET, RefundStepStatus.SUCCEEDED)));

        // when
        RefundResult sut = saga.resume(REFUND_ID);

        // then
        assertThat(sut.status()).isEqualTo("PROCESSING");
        then(walletRefundRequestPort).should(never()).requestRefund(any());
        then(refundStepPersistencePort).should(never()).update(any());
    }

    // 주문 줄이 없는(있을 수 없는 상태) 환불에 기다리는 시각이 지난 지갑 결과 대기 줄이 남았다. 계획은 멈춤을 정한다.
    // 그대로 두면 알람이 매 회차 맨 앞에 다시 뽑으므로 멈춤으로 바꾼다(#577)
    @Test
    void 다시_진행할_수_없는_환불의_기다리는_시각이_지난_결과_대기_줄은_멈춤으로_바꾼다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));
        given(refundStepPersistencePort.findAllByRefundId(REFUND_ID)).willReturn(List.of(overdueAwaitingWallet()));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.PROCESSING)));

        // when
        saga.resume(REFUND_ID);

        // then
        then(walletRefundRequestPort).should(never()).requestRefund(any());
        ArgumentCaptor<RefundStep> captor = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().update(captor.capture());
        RefundStep stopped = captor.getValue();
        assertThat(stopped).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(stopped.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(stopped.getLastReason()).isEqualTo(RefundSagaService.BLOCKED_BY_STOPPED_STEP);
        });
    }

    private static RefundStep overdueAwaitingWallet() {
        return RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT,
                3, NOW, null, NOW.minusMinutes(10));
    }

    private static Refund refund(RefundStatus status) {
        return Refund.restore(REFUND_ID, 1_000L, 101L, 2, 2_000L, 5L, 55L, "감자", "1kg", 10_000L, 20_000L,
                0L, 0L, 0L, null, status, null, null, null, null, null);
    }

    private static RefundStep step(RefundStepType type, RefundStepStatus status) {
        LocalDateTime nextCheckAt = status.isFinished() ? null : NOW.plusMinutes(1);
        return RefundStep.restore(type.ordinal() + 1L, REFUND_ID, type, status, 1, nextCheckAt, null, NOW);
    }
}
