package com.ok.refund.application.service;

import com.ok.refund.application.port.in.OrderRefundRevertFailedCommand;
import com.ok.refund.application.port.in.OrderRefundRevertedCommand;
import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.in.WalletRefundCompletedCommand;
import com.ok.refund.application.port.in.WalletRefundFailedCommand;
import com.ok.refund.application.port.in.WalletRevertCompletedCommand;
import com.ok.refund.application.port.in.WalletRevertFailedCommand;
import com.ok.refund.application.port.out.OrderCancellationPort;
import com.ok.refund.application.port.out.OrderRevertRequest;
import com.ok.refund.application.port.out.OrderRevertRequestPort;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

// 주문 되돌리기(#622): 예시는 환불 7번. 주문 1000번의 주문 상품 101번 2개(20,000원)를 취소해 주문에 취소를 반영했다.
// 지갑이 반환에 실패하거나, 재고가 거절돼 돌려준 돈을 다시 가져오면 주문에 취소 기록을 되돌려 달라고 보낸다
@ExtendWith(MockitoExtension.class)
class RefundSagaServiceOrderRevertTest {

    private static final Long REFUND_ID = 7L;
    private static final Long ORDER_ID = 1_000L;
    private static final Long ORDER_ITEM_ID = 101L;
    private static final Long BUYER_ID = 2_000L;
    private static final int QUANTITY = 2;
    private static final long AMOUNT = 20_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 10, 0);
    private static final OrderRevertRequest REVERT_REQUEST = new OrderRevertRequest(REFUND_ID, ORDER_ID, ORDER_ITEM_ID, QUANTITY);

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
    @Mock
    OrderRevertRequestPort orderRevertRequestPort;

    RefundSagaService saga;

    @BeforeEach
    void setUp() {
        saga = new RefundSagaService(orderCancellationPort, walletRefundRequestPort, productStockReleasePort,
                orderRevertRequestPort, refundPersistencePort, refundStepPersistencePort, CLOCK, new NoOpTransactionManager());
    }

    // ---- 되돌리기를 시작하는 경우 ----

    // 지갑이 반환에 실패했으면 돈이 나가지 않았다. 지갑 줄을 멈춘 같은 구간에서 되돌리기 줄을 만들고 바로 보낸다
    @Test
    void 지갑이_반환에_실패하면_지갑_줄을_멈추고_주문_되돌리기를_바로_요청한다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        // 지갑 결과는 재고 단계가 시작됐는지 먼저 본다(시작 전이라 없음)
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.PRODUCT)).willReturn(Optional.empty());
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET))
                .willReturn(Optional.of(awaiting(RefundStepType.WALLET)));

        // when
        saga.recordWalletRefundFailure(new WalletRefundFailedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT, "PAYMENT_NOT_FOUND"));

        // then
        then(orderRevertRequestPort).should().requestRevert(REVERT_REQUEST);
        RefundStep created = savedStep();
        assertThat(created).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(created.getType()).isEqualTo(RefundStepType.ORDER_REVERT);
            softly.assertThat(created.getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
        });
        ArgumentCaptor<RefundStep> updated = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should(atLeastOnce()).update(updated.capture());
        RefundStep wallet = updated.getAllValues().getFirst();
        assertThat(wallet).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(wallet.getType()).isEqualTo(RefundStepType.WALLET);
            softly.assertThat(wallet.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(wallet.getLastReason()).isEqualTo("PAYMENT_NOT_FOUND");
        });
    }

    // 결과 불일치는 지갑이 실패했는지 알 수 없어 주문을 되돌리지 않는다(#622 결정 D2, 이슈 본문 "RESULT_MISMATCH는 지갑 실패가 아니다")
    @Test
    void 지갑_실패_메시지의_금액이_다르면_불일치로_멈추고_주문은_되돌리지_않는다() {
        // given
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        // 지갑 결과는 재고 단계가 시작됐는지 먼저 본다(시작 전이라 없음)
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.PRODUCT)).willReturn(Optional.empty());
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET))
                .willReturn(Optional.of(awaiting(RefundStepType.WALLET)));

        // when
        saga.recordWalletRefundFailure(new WalletRefundFailedCommand(REFUND_ID, ORDER_ID, BUYER_ID, 19_000L, "PAYMENT_NOT_FOUND"));

        // then
        then(orderRevertRequestPort).should(never()).requestRevert(any());
        then(refundStepPersistencePort).should(never()).saveNew(any());
    }

    // 이미 다른 이유(예: 발행 횟수를 다 씀)로 멈춘 지갑 줄에 실패가 와도 지갑이 실패했다고 확정할 수 없어 사람이 본다(#622 결정 D2)
    @Test
    void 지갑_줄이_이미_멈춰_있으면_실패가_와도_주문을_되돌리지_않는다() {
        // given
        RefundStep stopped = RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.STOPPED, 5, null,
                "IllegalStateException", NOW.minusMinutes(10));
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        // 지갑 결과는 재고 단계가 시작됐는지 먼저 본다(시작 전이라 없음)
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.PRODUCT)).willReturn(Optional.empty());
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET)).willReturn(Optional.of(stopped));

        // when
        saga.recordWalletRefundFailure(new WalletRefundFailedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT, "PAYMENT_NOT_FOUND"));

        // then
        then(orderRevertRequestPort).should(never()).requestRevert(any());
        then(refundStepPersistencePort).should(never()).saveNew(any());
    }

    // 바로 보내기가 실패해도 지갑 실패 기록은 이미 커밋돼 있다. 되돌리기 줄을 할 차례로 남겨 알람이 다시 보내고, 예외는 올리지 않는다
    // (올리면 지갑 실패 메시지를 오류 처리기가 다시 시도하지만, 같은 결과라 넘겨질 뿐이다)
    @Test
    void 주문_되돌리기를_바로_보내지_못하면_되돌리기_줄을_할_차례로_남기고_예외를_올리지_않는다() {
        // given
        RefundStep orderRevert = RefundStep.start(REFUND_ID, RefundStepType.ORDER_REVERT, NOW);
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        // 지갑 결과는 재고 단계가 시작됐는지 먼저 본다(시작 전이라 없음)
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.PRODUCT)).willReturn(Optional.empty());
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET))
                .willReturn(Optional.of(awaiting(RefundStepType.WALLET)));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.ORDER_REVERT))
                .willReturn(Optional.empty(), Optional.of(orderRevert));
        willThrow(new IllegalStateException("주문 되돌리기 요청 발행에 실패했습니다(테스트)."))
                .given(orderRevertRequestPort).requestRevert(any());

        // when
        saga.recordWalletRefundFailure(new WalletRefundFailedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT, "PAYMENT_NOT_FOUND"));

        // then
        assertThat(orderRevert).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(orderRevert.getStatus()).isEqualTo(RefundStepStatus.READY);
            softly.assertThat(orderRevert.getLastReason()).isEqualTo("IllegalStateException");
        });
    }

    // 돈을 다시 가져왔으면 되돌리는 순서(지갑 → 주문)대로 이어서 주문을 되돌린다
    @Test
    void 지갑이_돈을_다시_가져오면_회수_줄을_성공으로_남기고_주문_되돌리기를_바로_요청한다() {
        // given
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET_REVERT))
                .willReturn(Optional.of(awaiting(RefundStepType.WALLET_REVERT)));

        // when
        saga.recordWalletRevertCompleted(new WalletRevertCompletedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT));

        // then
        then(orderRevertRequestPort).should().requestRevert(REVERT_REQUEST);
        assertThat(savedStep().getType()).isEqualTo(RefundStepType.ORDER_REVERT);
    }

    // 돈을 다시 가져오지 못했으면(구매자가 이미 씀) 주문을 되돌리지 않는다. 돈은 구매자에게 있는데 주문만 되돌리면 판매자가 상품을 보내야 한다
    @Test
    void 지갑이_돈을_다시_가져오지_못하면_주문을_되돌리지_않는다() {
        // given
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET_REVERT))
                .willReturn(Optional.of(awaiting(RefundStepType.WALLET_REVERT)));

        // when
        saga.recordWalletRevertFailure(new WalletRevertFailedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT, "INSUFFICIENT_BALANCE"));

        // then
        then(orderRevertRequestPort).should(never()).requestRevert(any());
        then(refundStepPersistencePort).should(never()).saveNew(any());
    }

    // 지갑은 같은 요청에 처음 결과만 다시 보내기로 약속했다. 어겨 완료가 늦게 와도, 주문을 되돌리는 중에 재고·완료로 가면
    // 상품은 배송되는데 돈까지 나가므로 재고를 부르지 않고 사람이 보게 한다(#622 검토)
    @Test
    void 지갑_실패로_되돌리기를_시작한_뒤_같은_환불의_지갑_완료가_오면_재고를_부르지_않고_되돌리기_줄을_멈춘다() {
        // given
        RefundStep stoppedByFailure = RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.STOPPED, 1, null,
                "PAYMENT_NOT_FOUND", NOW.minusMinutes(3));
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.PRODUCT)).willReturn(Optional.empty());
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET)).willReturn(Optional.of(stoppedByFailure));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.ORDER_REVERT))
                .willReturn(Optional.of(awaiting(RefundStepType.ORDER_REVERT)));

        // when
        saga.completeAfterWalletRefund(new WalletRefundCompletedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT));

        // then
        then(productStockReleasePort).should(never()).releaseStock(any());
        then(refundStepPersistencePort).should(never()).saveNew(any());
        then(refundPersistencePort).should(never()).update(any());
        // 멈춰 두면 주문 결과가 와도 실패로 끝나지 않고 처리 중으로 남아 찾기 알람이 사람에게 알린다(투표 반영)
        RefundStep orderRevert = updatedStep();
        assertThat(orderRevert).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(orderRevert.getType()).isEqualTo(RefundStepType.ORDER_REVERT);
            softly.assertThat(orderRevert.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(orderRevert.getLastReason()).isEqualTo(RefundSagaService.WALLET_COMPLETED_LATE);
        });
    }

    // 회수 성공 경로는 지갑 줄이 성공이다. 지갑은 같은 refundId에 처음 결과(완료)를 다시 보내므로 정상 중복이다.
    // 여기서 되돌리기 줄을 멈추면 주문의 진짜 결과를 버려 끝날 환불이 처리 중에 갇힌다(투표 지적)
    @Test
    void 회수_뒤_되돌리는_중에_같은_지갑_완료가_다시_오면_되돌리기_줄을_건드리지_않는다() {
        // given
        RefundStep productStopped = RefundStep.restore(3L, REFUND_ID, RefundStepType.PRODUCT, RefundStepStatus.STOPPED, 1, null,
                "PRODUCT_RELEASE_REFUSED", NOW.minusMinutes(3));
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.PRODUCT)).willReturn(Optional.of(productStopped));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET))
                .willReturn(Optional.of(step(RefundStepType.WALLET, RefundStepStatus.SUCCEEDED)));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.ORDER_REVERT))
                .willReturn(Optional.of(awaiting(RefundStepType.ORDER_REVERT)));

        // when
        saga.completeAfterWalletRefund(new WalletRefundCompletedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT));

        // then
        then(productStockReleasePort).should(never()).releaseStock(any());
        then(refundStepPersistencePort).should(never()).update(any());
        then(refundStepPersistencePort).should(never()).saveNew(any());
    }

    // 앞선 결과 불일치로 지갑 줄이 멈춘 채 맞는 완료를 받아 재고로 간 회수 경로(#544 결정 8)다. 지갑 줄은 멈춤이지만 재고 줄이 있어
    // 지갑 실패 경로가 아니다. 같은 완료가 다시 와도 정상 중복이라 되돌리기 줄을 멈추지 않는다(2라운드 재검 지적)
    @Test
    void 지갑_줄이_불일치로_멈춘_회수_경로에_같은_지갑_완료가_다시_와도_되돌리기_줄을_건드리지_않는다() {
        // given
        RefundStep productStopped = RefundStep.restore(3L, REFUND_ID, RefundStepType.PRODUCT, RefundStepStatus.STOPPED, 1, null,
                "PRODUCT_RELEASE_REFUSED", NOW.minusMinutes(3));
        RefundStep walletMismatch = RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.STOPPED, 1, null,
                RefundSagaService.RESULT_MISMATCH, NOW.minusMinutes(5));
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.PRODUCT)).willReturn(Optional.of(productStopped));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET)).willReturn(Optional.of(walletMismatch));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.ORDER_REVERT))
                .willReturn(Optional.of(awaiting(RefundStepType.ORDER_REVERT)));

        // when
        saga.completeAfterWalletRefund(new WalletRefundCompletedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT));

        // then
        then(productStockReleasePort).should(never()).releaseStock(any());
        then(refundStepPersistencePort).should(never()).update(any());
    }

    // 되돌리기 줄이 이미 주문의 실패(예: 이미 구매확정)로 끝났으면 늦은 지갑 완료가 와도 줄을 고치지 않는다(끝 상태는 바뀌지 않음)
    @Test
    void 되돌리기_줄이_이미_끝난_지갑_실패_경로에_지갑_완료가_와도_줄을_고치지_않는다() {
        // given
        RefundStep stoppedByFailure = RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.STOPPED, 1, null,
                "PAYMENT_NOT_FOUND", NOW.minusMinutes(5));
        RefundStep revertRefused = RefundStep.restore(5L, REFUND_ID, RefundStepType.ORDER_REVERT, RefundStepStatus.STOPPED, 1, null,
                "ALREADY_CONFIRMED", NOW.minusMinutes(3));
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.PRODUCT)).willReturn(Optional.empty());
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.WALLET)).willReturn(Optional.of(stoppedByFailure));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.ORDER_REVERT)).willReturn(Optional.of(revertRefused));

        // when
        saga.completeAfterWalletRefund(new WalletRefundCompletedCommand(REFUND_ID, ORDER_ID, BUYER_ID, AMOUNT));

        // then
        then(productStockReleasePort).should(never()).releaseStock(any());
        then(refundStepPersistencePort).should(never()).update(any());
        assertThat(revertRefused.getLastReason()).isEqualTo("ALREADY_CONFIRMED");
    }

    // ---- 알람이 깨운 다시 보내기 ----

    // 결과를 5분 기다려도 오지 않으면 같은 refundId로 다시 보낸다(#577 규칙). 앞의 멈춘 지갑 줄 때문에 막히지 않는다
    @Test
    void 되돌리기_결과를_기다리는_시각이_지나면_알람이_같은_되돌리기를_다시_요청한다() {
        // given
        RefundStep orderRevert = RefundStep.restore(3L, REFUND_ID, RefundStepType.ORDER_REVERT, RefundStepStatus.AWAITING_RESULT,
                1, NOW, null, NOW.minusMinutes(5), NOW.minusMinutes(6));
        given(refundPersistencePort.findById(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundStepPersistencePort.findAllByRefundId(REFUND_ID)).willReturn(List.of(
                step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED),
                RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.STOPPED, 1, null, "PAYMENT_NOT_FOUND", NOW),
                orderRevert));
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.ORDER_REVERT)).willReturn(Optional.of(orderRevert));

        // when
        RefundResult sut = saga.resume(REFUND_ID);

        // then
        assertThat(sut.status()).isEqualTo("PROCESSING");
        then(orderRevertRequestPort).should().requestRevert(REVERT_REQUEST);
        then(walletRefundRequestPort).should(never()).requestRefund(any());
    }

    // ---- 주문이 보낸 되돌리기 결과 ----

    @Test
    void 주문이_되돌리면_되돌리기_줄을_성공으로_남기고_환불을_실패로_끝낸다() {
        // given
        givenLockedWithOrderRevert(awaiting(RefundStepType.ORDER_REVERT));

        // when
        saga.recordOrderRefundReverted(new OrderRefundRevertedCommand(REFUND_ID, ORDER_ID, ORDER_ITEM_ID));

        // then
        assertThat(updatedStep().getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
        ArgumentCaptor<Refund> refund = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().update(refund.capture());
        assertThat(refund.getValue()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(refund.getValue().getStatus()).isEqualTo(RefundStatus.FAILED);
            softly.assertThat(refund.getValue().getRejectReason()).isEqualTo(Refund.FAILED_REASON);
            softly.assertThat(refund.getValue().getActiveOrderItemId()).isNull();
        });
    }

    // 되돌리기를 또 되돌리지 않는다(PR #537). 이유를 남기고 처리 중으로 두어 멈춘 환불 찾기가 알린다
    @Test
    void 주문이_되돌리지_못하면_주문이_알려_준_이유로_되돌리기_줄을_멈추고_환불은_처리_중으로_둔다() {
        // given
        givenLockedWithOrderRevert(awaiting(RefundStepType.ORDER_REVERT));

        // when
        saga.recordOrderRefundRevertFailure(new OrderRefundRevertFailedCommand(REFUND_ID, ORDER_ID, ORDER_ITEM_ID, "ALREADY_CONFIRMED"));

        // then
        RefundStep orderRevert = updatedStep();
        assertThat(orderRevert).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(orderRevert.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(orderRevert.getLastReason()).isEqualTo("ALREADY_CONFIRMED");
        });
        then(refundPersistencePort).should(never()).update(any());
    }

    @Test
    void 되돌림_결과의_주문_상품이_환불과_다르면_불일치로_멈추고_실패로_끝내지_않는다() {
        // given
        givenLockedWithOrderRevert(awaiting(RefundStepType.ORDER_REVERT));

        // when
        saga.recordOrderRefundReverted(new OrderRefundRevertedCommand(REFUND_ID, ORDER_ID, 102L));

        // then
        RefundStep orderRevert = updatedStep();
        assertThat(orderRevert).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(orderRevert.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(orderRevert.getLastReason()).isEqualTo(RefundSagaService.RESULT_MISMATCH);
        });
        then(refundPersistencePort).should(never()).update(any());
    }

    // 주문은 같은 refundId에 결과를 다시 보낼 수 있다. 이미 실패로 끝난 환불은 다시 쓰지 않는다
    @Test
    void 이미_실패로_끝난_환불에_같은_되돌림이_다시_와도_쓰지_않는다() {
        // given
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund(RefundStatus.FAILED)));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.ORDER_REVERT))
                .willReturn(Optional.of(step(RefundStepType.ORDER_REVERT, RefundStepStatus.SUCCEEDED)));

        // when
        saga.recordOrderRefundReverted(new OrderRefundRevertedCommand(REFUND_ID, ORDER_ID, ORDER_ITEM_ID));

        // then
        then(refundStepPersistencePort).should(never()).update(any());
        then(refundPersistencePort).should(never()).update(any());
    }

    private void givenLockedWithOrderRevert(RefundStep orderRevert) {
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(processing()));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.ORDER_REVERT)).willReturn(Optional.of(orderRevert));
    }

    private RefundStep savedStep() {
        ArgumentCaptor<RefundStep> captor = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().saveNew(captor.capture());
        return captor.getValue();
    }

    private RefundStep updatedStep() {
        ArgumentCaptor<RefundStep> captor = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().update(captor.capture());
        return captor.getValue();
    }

    private static RefundStep awaiting(RefundStepType type) {
        return RefundStep.restore(type.ordinal() + 1L, REFUND_ID, type, RefundStepStatus.AWAITING_RESULT,
                1, NOW.plusMinutes(5), null, NOW, NOW.minusMinutes(1));
    }

    private static Refund processing() {
        return refund(RefundStatus.PROCESSING);
    }

    private static Refund refund(RefundStatus status) {
        return Refund.restore(REFUND_ID, ORDER_ID, ORDER_ITEM_ID, QUANTITY, BUYER_ID, 5L, 55L, "감자", "1kg", 10_000L, AMOUNT,
                0L, 0L, 0L, null, status, null, null, null, null, null);
    }

    private static RefundStep step(RefundStepType type, RefundStepStatus status) {
        LocalDateTime nextCheckAt = status.isFinished() ? null : NOW.plusMinutes(1);
        return RefundStep.restore(type.ordinal() + 1L, REFUND_ID, type, status, 1, nextCheckAt, null, NOW);
    }
}
