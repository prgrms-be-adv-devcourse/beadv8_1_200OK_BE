package com.ok.delivery.adapter.in.event;

import com.ok.delivery.application.dto.DeliveryOutboxRecordedEvent;
import com.ok.delivery.application.port.in.DeliveryOutboxRelayUseCase;
import com.ok.delivery.config.DeliveryAsyncConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class DeliveryOutboxSendListenerTest {

    private static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");
    private static final Duration MIN_AGE = Duration.ofSeconds(10);
    private static final Long OUTBOX_ID = 7L;

    @Mock
    DeliveryOutboxRelayUseCase deliveryOutboxRelayUseCase;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private DeliveryOutboxSendListener sut;

    @BeforeEach
    void setUp() {
        sut = new DeliveryOutboxSendListener(deliveryOutboxRelayUseCase, Clock.fixed(NOW, ZoneOffset.UTC), MIN_AGE, meterRegistry);
    }

    @ParameterizedTest
    @MethodSource("min_age보다_짧게_기다린_시간")
    void 줄을_남긴_지_min_age가_지나지_않았으면_바로_보낸다(Duration waited) {
        // given
        DeliveryOutboxRecordedEvent event = new DeliveryOutboxRecordedEvent(OUTBOX_ID, NOW.minus(waited));

        // when
        sut.on(event);

        // then
        then(deliveryOutboxRelayUseCase).should().send(OUTBOX_ID);
        assertThat(staleCount()).isZero();
    }

    static Stream<Duration> min_age보다_짧게_기다린_시간() {
        return Stream.of(Duration.ZERO, Duration.ofMillis(9_999));
    }

    @ParameterizedTest
    @MethodSource("min_age_이상_기다린_시간")
    void 대기열에서_min_age_이상_기다렸으면_보내지_않고_발송_작업에_맡긴다(Duration waited) {
        // given
        DeliveryOutboxRecordedEvent event = new DeliveryOutboxRecordedEvent(OUTBOX_ID, NOW.minus(waited));

        // when
        sut.on(event);

        // then: 줄을 읽지도 않는다(대기열이 밀렸을 때 커넥션을 쓰지 않음)
        then(deliveryOutboxRelayUseCase).shouldHaveNoInteractions();
        assertThat(staleCount()).isEqualTo(1.0);
    }

    static Stream<Duration> min_age_이상_기다린_시간() {
        return Stream.of(MIN_AGE, Duration.ofSeconds(17));
    }

    private double staleCount() {
        return meterRegistry.get(DeliveryAsyncConfig.OUTBOX_SEND_DROPPED_METRIC)
                .tag("reason", DeliveryAsyncConfig.DROP_REASON_STALE)
                .counter()
                .count();
    }
}
