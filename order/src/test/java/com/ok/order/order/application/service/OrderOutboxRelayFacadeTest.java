package com.ok.order.order.application.service;

import com.ok.order.order.application.dto.OrderOutboxMessage;
import com.ok.order.order.application.port.out.OrderMessageSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderOutboxRelayFacadeTest {

    private static final String TOPIC = "order.test.v1";
    private static final Duration MIN_AGE = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-11-20T01:00:00Z");

    @Mock
    OrderOutboxService orderOutboxService;

    @Mock
    OrderMessageSender orderMessageSender;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private OrderOutboxRelayFacade sut;

    @BeforeEach
    void setUp() {
        sut = new OrderOutboxRelayFacade(orderOutboxService, orderMessageSender, Clock.fixed(NOW, ZoneOffset.UTC), MIN_AGE,
                meterRegistry);
    }

    private static OrderOutboxMessage message(long id, String key) {
        return new OrderOutboxMessage(id, TOPIC, key, "{\"id\":" + id + "}", 0);
    }

    @Test
    void 보낼_차례인_줄을_쌓인_순서대로_보내고_보냄으로_표시한다() {
        // given
        given(orderOutboxService.findPending(100, MIN_AGE)).willReturn(List.of(message(1L, "10"), message(2L, "11")));

        // when
        int sent = sut.relayPending(100, MIN_AGE);

        // then
        assertThat(sent).isEqualTo(2);
        then(orderOutboxService).should().markSent(1L);
        then(orderOutboxService).should().markSent(2L);
    }

    // 실패는 거의 브로커 장애라 뒤 줄도 실패할 뿐이고, 멈춰야 같은 키의 뒤 줄이 앞 줄보다 먼저 나가지 않는다
    @Test
    void 한_줄이_실패하면_실패를_기록하고_그_회차는_뒤_줄을_보내지_않는다() {
        // given
        given(orderOutboxService.findPending(100, MIN_AGE))
                .willReturn(List.of(message(1L, "10"), message(2L, "10"), message(3L, "11")));
        // 두 번째 줄만 실패한다. 인자마다 따로 stub하면 엄격한 stub 검사가 다른 인자의 호출을 오류로 본다
        willAnswer(invocation -> {
            if ("{\"id\":2}".equals(invocation.getArgument(2))) {
                throw new IllegalStateException("브로커 거절");
            }
            return null;
        }).given(orderMessageSender).send(anyString(), anyString(), anyString());

        // when
        int sent = sut.relayPending(100, MIN_AGE);

        // then
        assertThat(sent).isEqualTo(1);
        then(orderOutboxService).should().markSent(1L);
        then(orderOutboxService).should().recordFailure(eq(2L), anyString());
        then(orderMessageSender).should(never()).send(TOPIC, "11", "{\"id\":3}");
    }

    @Test
    void 다른_발송이_먼저_보냄으로_바꾼_줄이_있어도_그_회차를_멈추지_않는다() {
        // given: 첫 줄은 커밋 직후 발송이 먼저 보냄으로 바꿨다
        given(orderOutboxService.findPending(100, MIN_AGE)).willReturn(List.of(message(1L, "10"), message(2L, "11")));
        given(orderOutboxService.markSent(1L)).willReturn(false);
        given(orderOutboxService.markSent(2L)).willReturn(true);

        // when
        int sent = sut.relayPending(100, MIN_AGE);

        // then
        assertThat(sent).isEqualTo(2);
        then(orderMessageSender).should().send(TOPIC, "11", "{\"id\":2}");
    }

    @Test
    void 커밋_직후_발송은_방금_남긴_줄을_보내고_보냄으로_표시한다() {
        // given
        given(orderOutboxService.findPendingById(1L)).willReturn(Optional.of(message(1L, "10")));

        // when
        sut.relayAfterCommit(1L, NOW);

        // then
        then(orderMessageSender).should().send(TOPIC, "10", "{\"id\":1}");
        then(orderOutboxService).should().markSent(1L);
    }

    @ParameterizedTest
    @MethodSource("min_age보다_짧게_기다린_시간")
    void 대기열에서_min_age보다_짧게_기다렸으면_보낸다(Duration waited) {
        // given
        given(orderOutboxService.findPendingById(1L)).willReturn(Optional.of(message(1L, "10")));

        // when
        sut.relayAfterCommit(1L, NOW.minus(waited));

        // then
        then(orderMessageSender).should().send(TOPIC, "10", "{\"id\":1}");
        assertThat(staleCount()).isZero();
    }

    static Stream<Duration> min_age보다_짧게_기다린_시간() {
        return Stream.of(Duration.ZERO, Duration.ofMillis(9_999));
    }

    // 재발송은 min-age가 지난 줄을 집으므로, 그보다 오래 기다린 작업이 보내면 재발송과 겹쳐 받는 쪽이 중복을 받는다(#794)
    @ParameterizedTest
    @MethodSource("min_age_이상_기다린_시간")
    void 대기열에서_min_age_이상_기다렸으면_줄을_읽지_않고_재발송에_맡긴다(Duration waited) {
        // when
        sut.relayAfterCommit(1L, NOW.minus(waited));

        // then: 줄을 읽지도 않는다(대기열이 밀렸을 때 커넥션을 쓰지 않음)
        then(orderOutboxService).shouldHaveNoInteractions();
        then(orderMessageSender).shouldHaveNoInteractions();
        assertThat(staleCount()).isEqualTo(1.0);
    }

    static Stream<Duration> min_age_이상_기다린_시간() {
        return Stream.of(MIN_AGE, Duration.ofSeconds(17));
    }

    @Test
    void 커밋_직후_발송_전에_재발송이_먼저_보냈으면_다시_보내지_않는다() {
        // given
        given(orderOutboxService.findPendingById(1L)).willReturn(Optional.empty());

        // when
        sut.relayAfterCommit(1L, NOW);

        // then
        then(orderMessageSender).should(never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void 커밋_직후_발송이_실패해도_예외를_던지지_않고_실패를_기록해_재발송에_맡긴다() {
        // given
        given(orderOutboxService.findPendingById(1L)).willReturn(Optional.of(message(1L, "10")));
        willThrow(new IllegalStateException("브로커 거절")).given(orderMessageSender).send(TOPIC, "10", "{\"id\":1}");

        // when & then
        assertThatCode(() -> sut.relayAfterCommit(1L, NOW)).doesNotThrowAnyException();
        then(orderOutboxService).should().recordFailure(eq(1L), anyString());
        then(orderOutboxService).should(never()).markSent(anyLong());
    }

    @Test
    void 실패_원인에는_바깥_예외와_맨_안쪽_원인을_함께_남긴다() {
        // given
        IllegalStateException e = new IllegalStateException("주문 Kafka 발행에 실패했습니다",
                new RuntimeException("중간", new TimeoutException()));

        // when
        String sut = OrderOutboxRelayFacade.failureReason(e);

        // then
        assertThat(sut).isEqualTo("IllegalStateException: 주문 Kafka 발행에 실패했습니다 / TimeoutException");
    }

    private double staleCount() {
        return meterRegistry.get(OrderOutboxRelayFacade.SEND_DROPPED_METRIC)
                .tag("reason", OrderOutboxRelayFacade.DROP_REASON_STALE)
                .counter()
                .count();
    }
}
