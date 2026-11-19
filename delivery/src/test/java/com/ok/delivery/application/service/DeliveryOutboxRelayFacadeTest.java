package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.DeliveryOutboxMessage;
import com.ok.delivery.application.port.out.DeliveryMessageSender;
import com.ok.delivery.config.DeliveryAsyncConfig;
import com.ok.delivery.domain.DeliveryOutboxEventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

// 정상 발송(실제 Kafka), 즉시 발송, 실패 뒤 다음 실행의 재발송은 DeliveryOutboxKafkaIntegrationTest가 본다.
// 여기서는 실패했을 때 무엇을 남기고 어디서 멈추는지, 즉시 발송이 예외를 올리지 않는지 본다
@ExtendWith(MockitoExtension.class)
class DeliveryOutboxRelayFacadeTest {

    private static final int BATCH_SIZE = 100;
    private static final Duration MIN_AGE = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");
    private static final DeliveryOutboxMessage FIRST =
            new DeliveryOutboxMessage(10L, DeliveryOutboxEventType.SHIPMENT_DISPATCHED, "2", "{\"orderId\":2}", 0);
    private static final DeliveryOutboxMessage SECOND =
            new DeliveryOutboxMessage(11L, DeliveryOutboxEventType.SHIPMENT_DISPATCHED, "3", "{\"orderId\":3}", 0);

    @Mock
    DeliveryOutboxService deliveryOutboxService;

    @Mock
    DeliveryMessageSender deliveryMessageSender;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private DeliveryOutboxRelayFacade sut;

    @BeforeEach
    void setUp() {
        sut = new DeliveryOutboxRelayFacade(deliveryOutboxService, deliveryMessageSender,
                Clock.fixed(NOW, ZoneOffset.UTC), MIN_AGE, meterRegistry);
    }

    @Test
    void 발송이_실패하면_실패를_기록하고_그_실행은_멈춰_뒤_줄을_보내지_않는다() {
        // given
        given(deliveryOutboxService.findPending(BATCH_SIZE, MIN_AGE)).willReturn(List.of(FIRST, SECOND));
        willThrow(new IllegalStateException("배송 Kafka 발행에 실패했습니다.", new TimeoutException("확인 대기 시간 초과")))
                .given(deliveryMessageSender).send(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, "2", "{\"orderId\":2}");

        // when
        int sent = sut.relayPending(BATCH_SIZE, MIN_AGE);

        // then
        assertThat(sent).isZero();
        then(deliveryOutboxService).should().recordFailure(10L,
                "IllegalStateException: 배송 Kafka 발행에 실패했습니다. / TimeoutException: 확인 대기 시간 초과");
        then(deliveryMessageSender).should(never()).send(any(), eq("3"), anyString());
        then(deliveryOutboxService).should(never()).markSent(anyLong());
    }

    @Test
    void 앞_줄을_보낸_뒤_실패하면_앞_줄만_보냄으로_남는다() {
        // given
        given(deliveryOutboxService.findPending(BATCH_SIZE, MIN_AGE)).willReturn(List.of(FIRST, SECOND));
        willDoNothing().given(deliveryMessageSender).send(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, "2", "{\"orderId\":2}");
        willThrow(new IllegalStateException("배송 Kafka 발행에 실패했습니다."))
                .given(deliveryMessageSender).send(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, "3", "{\"orderId\":3}");

        // when
        int sent = sut.relayPending(BATCH_SIZE, MIN_AGE);

        // then
        assertThat(sent).isEqualTo(1);
        then(deliveryOutboxService).should().markSent(10L);
        then(deliveryOutboxService).should(never()).markSent(11L);
        then(deliveryOutboxService).should().recordFailure(eq(11L), anyString());
    }

    @Test
    void 보낸_뒤_보냄_기록이_실패하면_예외를_올리고_실패로_기록하지_않는다() {
        // given: 메시지는 이미 나갔으므로 실패 횟수를 늘리지 않는다. 줄은 PENDING으로 남아 다음 실행에서 한 번 더 나간다
        given(deliveryOutboxService.findPending(BATCH_SIZE, MIN_AGE)).willReturn(List.of(FIRST));
        willThrow(new IllegalStateException("DB 오류")).given(deliveryOutboxService).markSent(10L);

        // when & then
        assertThatThrownBy(() -> sut.relayPending(BATCH_SIZE, MIN_AGE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 오류");
        then(deliveryOutboxService).should(never()).recordFailure(anyLong(), anyString());
    }

    @Test
    void 실패_기록마저_실패하면_원래_발송_실패를_붙여_예외를_올린다() {
        // given: Kafka와 DB가 함께 실패하는 경우. 발송 실패 원인을 잃지 않아야 한다
        given(deliveryOutboxService.findPending(BATCH_SIZE, MIN_AGE)).willReturn(List.of(FIRST));
        IllegalStateException sendFailure = new IllegalStateException("배송 Kafka 발행에 실패했습니다.");
        willThrow(sendFailure).given(deliveryMessageSender)
                .send(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, "2", "{\"orderId\":2}");
        willThrow(new DataAccessResourceFailureException("DB 연결 실패"))
                .given(deliveryOutboxService).recordFailure(eq(10L), anyString());

        // when & then
        assertThatThrownBy(() -> sut.relayPending(BATCH_SIZE, MIN_AGE))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasSuppressedException(sendFailure);
    }

    @Test
    void 즉시_발송이_실패하면_예외를_올리지_않고_실패를_기록해_줄을_발송_작업에_남긴다() {
        // given
        given(deliveryOutboxService.findPendingById(10L)).willReturn(Optional.of(FIRST));
        willThrow(new IllegalStateException("배송 Kafka 발행에 실패했습니다.", new TimeoutException("확인 대기 시간 초과")))
                .given(deliveryMessageSender).send(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, "2", "{\"orderId\":2}");

        // when
        sut.send(10L, NOW);

        // then
        then(deliveryOutboxService).should().recordFailure(10L,
                "IllegalStateException: 배송 Kafka 발행에 실패했습니다. / TimeoutException: 확인 대기 시간 초과");
        then(deliveryOutboxService).should(never()).markSent(anyLong());
    }

    @Test
    void 즉시_발송할_줄이_이미_보낸_줄이면_보내지_않는다() {
        // given
        given(deliveryOutboxService.findPendingById(10L)).willReturn(Optional.empty());

        // when
        sut.send(10L, NOW);

        // then
        then(deliveryMessageSender).shouldHaveNoInteractions();
        then(deliveryOutboxService).should(never()).markSent(anyLong());
    }

    @ParameterizedTest
    @MethodSource("min_age보다_짧게_기다린_시간")
    void 줄을_남긴_지_min_age가_지나지_않았으면_바로_보낸다(Duration waited) {
        // given
        given(deliveryOutboxService.findPendingById(10L)).willReturn(Optional.of(FIRST));
        given(deliveryOutboxService.markSent(10L)).willReturn(true);

        // when
        sut.send(10L, NOW.minus(waited));

        // then
        then(deliveryMessageSender).should().send(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, "2", "{\"orderId\":2}");
        assertThat(staleCount()).isZero();
    }

    static Stream<Duration> min_age보다_짧게_기다린_시간() {
        return Stream.of(Duration.ZERO, Duration.ofMillis(9_999));
    }

    @ParameterizedTest
    @MethodSource("min_age_이상_기다린_시간")
    void 대기열에서_min_age_이상_기다렸으면_보내지_않고_발송_작업에_맡긴다(Duration waited) {
        // when
        sut.send(10L, NOW.minus(waited));

        // then: 줄을 읽지도 않는다(대기열이 밀렸을 때 커넥션을 쓰지 않음)
        then(deliveryOutboxService).shouldHaveNoInteractions();
        then(deliveryMessageSender).shouldHaveNoInteractions();
        assertThat(staleCount()).isEqualTo(1.0);
    }

    static Stream<Duration> min_age_이상_기다린_시간() {
        return Stream.of(MIN_AGE, Duration.ofSeconds(17));
    }

    @Test
    void 실패_원인은_원인_사슬_맨_안쪽까지_따라가_남긴다() {
        // given: 발송기 예외 → ExecutionException → 실제 브로커 오류 순으로 감싼 경우
        RuntimeException e = new IllegalStateException("배송 Kafka 발행에 실패했습니다.",
                new ExecutionException("감싼 예외", new IllegalArgumentException("토픽 권한 없음")));

        // when
        String sut = DeliveryOutboxRelayFacade.failureReason(e);

        // then
        assertThat(sut).isEqualTo("IllegalStateException: 배송 Kafka 발행에 실패했습니다. / IllegalArgumentException: 토픽 권한 없음");
    }

    @Test
    void 원인이_서로를_가리키는_순환이_있어도_끝나고_실패_원인을_남긴다() {
        // given: A → B → A 순환. 상한이 없으면 끝나지 않아 발송 스케줄러가 멈춘다
        RuntimeException first = new IllegalStateException("앞 예외");
        RuntimeException second = new IllegalArgumentException("뒤 예외", first);
        first.initCause(second);

        // when
        String sut = DeliveryOutboxRelayFacade.failureReason(first);

        // then: 상한에서 멈춘 위치가 순환의 어느 쪽인지는 상한 값에 따라 달라, 바깥 예외가 남는지만 본다
        assertThat(sut).startsWith("IllegalStateException: 앞 예외");
    }

    private double staleCount() {
        return meterRegistry.get(DeliveryAsyncConfig.OUTBOX_SEND_DROPPED_METRIC)
                .tag("reason", DeliveryAsyncConfig.DROP_REASON_STALE)
                .counter()
                .count();
    }
}
