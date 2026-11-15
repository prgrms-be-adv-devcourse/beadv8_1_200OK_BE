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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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

    private static Refund refund(RefundStatus status) {
        return Refund.restore(REFUND_ID, 1_000L, 101L, 2, 2_000L, 5L, 55L, "감자", "1kg", 10_000L, 20_000L,
                0L, 0L, 0L, null, status, null, null, null, null, null);
    }

    private static RefundStep step(RefundStepType type, RefundStepStatus status) {
        LocalDateTime nextCheckAt = status.isFinished() ? null : NOW.plusMinutes(1);
        return RefundStep.restore(type.ordinal() + 1L, REFUND_ID, type, status, 1, nextCheckAt, null, NOW);
    }
}
