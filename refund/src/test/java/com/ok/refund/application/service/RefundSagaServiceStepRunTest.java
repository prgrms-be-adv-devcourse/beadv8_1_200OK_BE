package com.ok.refund.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.out.OrderCancellationPort;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.application.port.out.WalletRefundRequestPort;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepStatus;
import com.ok.refund.domain.RefundStepType;
import com.ok.refund.domain.RefundShippingFee;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

// 기록 여부와 기록한 값이 이 클래스의 요구사항이라 영속 포트에 넘긴 값을 검증한다
@ExtendWith(MockitoExtension.class)
// 지휘자의 단계 실행 틀(runStep)만 호출 하나로 본다. 실패 기록과 이유 이름 규칙은 어느 단계든 같아서 입구를 거치지 않는다
class RefundSagaServiceStepRunTest {

    private static final Long REFUND_ID = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 10, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T01:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    RefundPersistencePort refundPersistencePort;
    @Mock
    RefundStepPersistencePort refundStepPersistencePort;
    // 단계 실행 틀은 호출을 받아 실행만 한다. 다른 모듈 포트는 지휘자 생성에만 필요하다
    @Mock
    OrderCancellationPort orderCancellationPort;
    @Mock
    WalletRefundRequestPort walletRefundRequestPort;
    @Mock
    ProductStockReleasePort productStockReleasePort;

    RefundSagaService sut;

    // 출력 문자열 검색은 스택에 찍힌 테스트 메서드 이름에도 "ERROR"가 있어 수준을 못 가린다. 로그 이벤트의 수준을 직접 받는다
    private final Logger runnerLogger = (Logger) LoggerFactory.getLogger(RefundSagaService.class);
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        sut = new RefundSagaService(orderCancellationPort, walletRefundRequestPort, productStockReleasePort,
                refundPersistencePort, refundStepPersistencePort, CLOCK, new NoOpTransactionManager());
    }

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        runnerLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        runnerLogger.detachAppender(logAppender);
    }

    @Test
    void 성공하면_결과를_돌려주고_기록하지_않는다() {
        // when
        String result = sut.runStep(REFUND_ID, RefundStepType.WALLET, () -> "OK");

        // then
        assertThat(result).isEqualTo("OK");
        then(refundPersistencePort).should(never()).findByIdForUpdate(any());
    }

    @Test
    void 실패하면_단계와_에러_코드를_기록하고_원래_예외를_던진다() {
        // given
        RestApiException failure = new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        givenRecordable(RefundStepType.ORDER);

        // when & then
        assertThatThrownBy(() -> sut.runStep(REFUND_ID, RefundStepType.ORDER, () -> {
            throw failure;
        })).isSameAs(failure);
        assertRecorded(RefundStepType.ORDER, "ORDER_API_FAILED");
    }

    // 그 단계 줄을 다시 할 차례로 되돌리고 이유와 다시 볼 시각을 남긴다. 환불 행은 바꾸지 않는다(결정 3)
    @Test
    void 실패하면_그_단계_줄을_할_차례로_되돌리고_이유를_남긴다() {
        // given
        givenProcessingRefund();
        RefundStep stepRow = RefundStep.start(REFUND_ID, RefundStepType.ORDER, NOW.minusMinutes(5));
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, RefundStepType.ORDER)).willReturn(Optional.of(stepRow));

        // when & then
        assertThatThrownBy(() -> sut.runStep(REFUND_ID, RefundStepType.ORDER, () -> {
            throw new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        })).isInstanceOfSatisfying(RestApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_API_FAILED));
        ArgumentCaptor<RefundStep> captor = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().update(captor.capture());
        RefundStep updated = captor.getValue();
        assertThat(updated).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(updated.getStatus()).isEqualTo(RefundStepStatus.READY);
            softly.assertThat(updated.getLastReason()).isEqualTo("ORDER_API_FAILED");
            softly.assertThat(updated.getNextCheckAt()).isEqualTo(LocalDateTime.of(2026, 11, 4, 10, 1));
        });
        then(refundPersistencePort).should(never()).update(any());
    }

    // 서비스는 다른 모듈을 부르기 전에 단계 줄을 만든다. 줄이 없으면 그 순서를 어긴 버그라 기록 실패로 드러나되,
    // 응답은 진짜 원인(원래 예외)이어야 한다
    @Test
    void 단계_줄이_없으면_기록_실패로_원래_예외에_붙인다() {
        // given
        givenProcessingRefund();
        RestApiException failure = new RestApiException(RefundErrorCode.ORDER_API_FAILED);

        // when & then
        assertThatThrownBy(() -> sut.runStep(REFUND_ID, RefundStepType.ORDER, () -> {
            throw failure;
        })).isSameAs(failure)
                .satisfies(e -> assertThat(e.getSuppressed()).singleElement()
                        .isInstanceOf(IllegalStateException.class)
                        .extracting(Throwable::getMessage)
                        .isEqualTo("실패를 기록할 단계 줄이 없습니다. refundId=7, step=ORDER"));
        then(refundPersistencePort).should(never()).update(any());
        then(refundStepPersistencePort).should(never()).update(any());
        then(refundStepPersistencePort).should(never()).saveNew(any());
    }

    // 예외 메시지에는 다른 모듈의 응답 내용이 들어갈 수 있어 메시지가 아니라 클래스 이름만 남는지 본다
    @Test
    void 에러_코드가_없는_예외면_클래스_이름을_기록한다() {
        // given
        IllegalStateException failure = new IllegalStateException("상대 서버 응답 본문");
        givenRecordable(RefundStepType.PRODUCT);

        // when & then
        assertThatThrownBy(() -> sut.runStep(REFUND_ID, RefundStepType.PRODUCT, () -> {
            throw failure;
        })).isSameAs(failure);
        assertRecorded(RefundStepType.PRODUCT, "IllegalStateException");
    }

    // 만일을 대비한 경우: 그 자리에서 만든 이름 없는 예외는 짧은 이름이 빈 값이라, 그대로 넘기면 기록이 거부돼 실패가 남지 않는다
    @Test
    void 이름_없는_예외면_패키지까지_붙은_이름을_기록한다() {
        // given
        RuntimeException failure = new RuntimeException("상대 서버 응답 본문") {
        };
        givenRecordable(RefundStepType.ORDER);

        // when & then
        assertThatThrownBy(() -> sut.runStep(REFUND_ID, RefundStepType.ORDER, () -> {
            throw failure;
        })).isSameAs(failure);
        assertRecorded(RefundStepType.ORDER, failure.getClass().getName());
    }

    // 칸(last_reason)이 100자라 넘는 이름을 그대로 넘기면 저장이 실패해 기록이 사라진다. 앞 100자만 남는지 본다
    @Test
    void 이유가_100자를_넘으면_앞_100자만_기록한다() {
        // given
        RuntimeException failure = new 이름이_아주_긴_예외_클래스는_실패_이유_칸의_길이를_넘을_수_있어서_앞부분만_남기는지_확인하려고_일부러_길게_지은_테스트_전용_예외_클래스_이름입니다_조금_더_길게_백자를_넘기도록();
        String name = failure.getClass().getSimpleName();
        givenRecordable(RefundStepType.PRODUCT);

        // when & then
        assertThatThrownBy(() -> sut.runStep(REFUND_ID, RefundStepType.PRODUCT, () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(name.length()).isGreaterThan(RefundStep.MAX_REASON_LENGTH);
        assertRecorded(RefundStepType.PRODUCT, name.substring(0, RefundStep.MAX_REASON_LENGTH));
    }

    private static class 이름이_아주_긴_예외_클래스는_실패_이유_칸의_길이를_넘을_수_있어서_앞부분만_남기는지_확인하려고_일부러_길게_지은_테스트_전용_예외_클래스_이름입니다_조금_더_길게_백자를_넘기도록
            extends RuntimeException {
    }

    // 기록 실패로 응답을 바꾸면 진짜 원인(다른 모듈 실패)이 가려진다. 원래 예외를 던지고 기록 실패는 거기에 붙어 있어야 한다
    @Test
    void 기록마저_실패하면_원래_예외에_붙여_던진다() {
        // given
        RestApiException failure = new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        CannotAcquireLockException recordError = new CannotAcquireLockException("잠금 대기 시간 초과");
        willThrow(recordError).given(refundPersistencePort).findByIdForUpdate(REFUND_ID);

        // when & then
        assertThatThrownBy(() -> sut.runStep(REFUND_ID, RefundStepType.WALLET, () -> {
            throw failure;
        })).isSameAs(failure)
                .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(recordError));
    }

    // 원래 예외가 RestApiException이면 ApiControllerAdvice는 로그를 남기지 않아, 이 ERROR 로그가 기록 실패의 유일한 흔적이다.
    // 운영자가 refundId로 찾을 수 있어야 하므로 식별자가 들어간 줄이 실제로 찍히는지 본다
    @Test
    void 기록마저_실패하면_환불_ID와_단계를_ERROR로_남긴다() {
        // given
        RestApiException failure = new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        willThrow(new CannotAcquireLockException("잠금 대기 시간 초과"))
                .given(refundPersistencePort).findByIdForUpdate(REFUND_ID);

        // when
        assertThatThrownBy(() -> sut.runStep(REFUND_ID, RefundStepType.ORDER, () -> {
            throw failure;
        })).isSameAs(failure);

        // then
        List<ILoggingEvent> events = logAppender.list;
        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            softly.assertThat(event.getFormattedMessage()).isEqualTo(
                    "환불 실패 단계 기록 실패. 멈춘 단계가 단계 줄에 남지 않음. refundId=7, step=ORDER, failureCode=ORDER_API_FAILED");
        });
    }

    // 아래 테스트들은 예전 환불 서비스의 실패 기록 테스트에서 옮겼다. 기록을 이 클래스가 직접 하게 되어서다
    @Test
    void 실패를_기록하면_환불은_그대로_두고_단계_줄에_이유가_남는다() {
        // given
        givenRecordable(RefundStepType.ORDER);

        // when
        sut.recordFailure(REFUND_ID, RefundStepType.ORDER, "ORDER_API_FAILED");

        // then: 명시적 저장(#481). update에 넘긴 값이 저장될 값이라 그 값을 본다
        assertRecorded(RefundStepType.ORDER, "ORDER_API_FAILED");
    }

    // 끝난 환불(그사이 완료·거절 등)에 실패를 남기면 끝난 환불이 멈춘 것처럼 보인다. 기록하지 않고 WARN만 남긴다(#616 결정 15)
    @Test
    void 처리_중이_아닌_환불에는_실패를_기록하지_않는다() {
        // given
        Refund requested = Refund.request(10L, 101L, 2, 1L, 5L, 55L, "감자", "1kg", 10_000L, null);
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(requested));

        // when: 예외 없이 끝난다. 원래 호출의 예외는 runStep이 그대로 올린다(#616 결정 15)
        sut.recordFailure(REFUND_ID, RefundStepType.ORDER, "ORDER_API_FAILED");

        // then
        then(refundStepPersistencePort).should(never()).update(any());
        then(refundStepPersistencePort).should(never()).findByRefundIdAndType(any(), any());
    }

    // refundId는 승인을 마친 시스템이 넘기는 값이라 없으면 사용자 잘못이 아닌 버그다. 404가 아니라 표준 예외(500)로 드러나야 한다
    @Test
    void 없는_환불에_실패를_기록하면_예외를_던진다() {
        // given
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.recordFailure(REFUND_ID, RefundStepType.ORDER, "ORDER_API_FAILED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("실패를 기록할 환불이 없습니다. refundId=7");
    }

    // 실패 기록은 처리 중인 환불에만 남길 수 있다. 승인까지 마친 환불을 잠금 조회 결과로 준다
    private void givenProcessingRefund() {
        Refund refund = Refund.request(10L, 101L, 2, 1L, 5L, 55L, "감자", "1kg", 10_000L, null);
        refund.approve(NOW, RefundShippingFee.none());
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));
    }

    // 처리 중인 환불과, 서비스가 호출 전에 만들어 둔 그 단계의 시도 중 줄을 준다
    private void givenRecordable(RefundStepType step) {
        givenProcessingRefund();
        given(refundStepPersistencePort.findByRefundIdAndType(REFUND_ID, step))
                .willReturn(Optional.of(RefundStep.start(REFUND_ID, step, NOW.minusMinutes(5))));
    }

    // 기록한 이유는 update에 넘긴 단계 줄에 담긴다(정확히 한 번). 환불 행은 바꾸지 않는다
    private void assertRecorded(RefundStepType step, String failureCode) {
        ArgumentCaptor<RefundStep> captor = ArgumentCaptor.forClass(RefundStep.class);
        then(refundStepPersistencePort).should().update(captor.capture());
        RefundStep updated = captor.getValue();
        assertThat(updated).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(updated.getType()).isEqualTo(step);
            softly.assertThat(updated.getStatus()).isEqualTo(RefundStepStatus.READY);
            softly.assertThat(updated.getLastReason()).isEqualTo(failureCode);
        });
        then(refundPersistencePort).should(never()).update(any());
    }
}
