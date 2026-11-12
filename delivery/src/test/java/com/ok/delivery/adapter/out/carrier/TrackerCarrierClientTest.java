package com.ok.delivery.adapter.out.carrier;

import com.ok.delivery.adapter.in.event.CarrierDeliveredEvent;
import com.ok.delivery.adapter.in.event.CarrierStatusReportedEvent;
import com.ok.delivery.adapter.in.event.CarrierTrackingGaveUpEvent;
import com.ok.delivery.domain.Carrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 실제 Delivery Tracker를 호출하지 않고 응답을 흉내 낸다. 응답 모양은 실제 API 호출로 확인한 값이다
// (진행 중·완료는 data.track.lastEvent.status.code, 오류는 HTTP 200 본문의 errors, 토큰 만료는 extensions.code=UNAUTHENTICATED)
class TrackerCarrierClientTest {

    private static final String GRAPHQL_URL = "https://tracker.test/graphql";
    private static final String AUTH_URL = "https://auth.tracker.test/oauth2/token";
    private static final String CARRIER_ID = "kr.epost";
    private static final String TRACKING_NUMBER = "1234567890123";
    private static final String TIME = "2026-11-08T10:49:26.000+09:00";
    private static final Duration MAX_TRACKING_PERIOD = Duration.ofDays(7);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 10, 0);
    // 상한(7일) 안인 출고 시각과 이미 넘은 출고 시각
    private static final LocalDateTime SHIPPED_WITHIN_LIMIT = NOW.minusDays(1);
    private static final LocalDateTime SHIPPED_OVER_LIMIT = NOW.minusDays(7).minusSeconds(1);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);

    // 조회는 스케줄러 스레드에서 돌아 이벤트를 쌓는 스레드와 읽는 스레드가 다르다
    private final List<Object> publishedEvents = new CopyOnWriteArrayList<>();
    private MockRestServiceServer server;
    private TrackerCarrierClient sut;

    @BeforeEach
    void setUp() {
        sut = newClient(Duration.ofHours(1));
    }

    @AfterEach
    void tearDown() {
        sut.destroy();
    }

    private TrackerCarrierClient newClient(Duration pollInterval) {
        return newClient(pollInterval, CLOCK);
    }

    private TrackerCarrierClient newClient(Duration pollInterval, Clock clock) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(false).build();
        TrackerProperties properties = new TrackerProperties(GRAPHQL_URL, AUTH_URL, "client-id", "client-secret",
                pollInterval, MAX_TRACKING_PERIOD, Duration.ofSeconds(1), Duration.ofSeconds(1));
        ApplicationEventPublisher publisher = publishedEvents::add;
        return new TrackerCarrierClient(builder.build(), properties, publisher, clock);
    }

    // 상태 판단

    @Test
    void 마지막_상태가_배송_완료이면_배송_완료를_알린다() {
        // given
        expectToken("token-1");
        expectTrack("token-1", trackResponse("DELIVERED"));

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);

        // then
        assertThat(publishedEvents).containsExactly(new CarrierDeliveredEvent(1L));
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("배송_완료가_아닌_상태")
    void 배송_완료가_아닌_상태이면_상태를_보고하고_배송_완료는_알리지_않는다(String statusCode) {
        // given
        expectToken("token-1");
        expectTrack("token-1", trackResponse(statusCode));

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);

        // then
        assertThat(publishedEvents).containsExactly(new CarrierStatusReportedEvent(1L, statusCode, TIME));
        assertThat(publishedEvents).extracting(event -> ((CarrierStatusReportedEvent) event).eventKey())
                .containsExactly(statusCode + ":" + TIME);
    }

    @Test
    void 같은_상태와_시각이_다시_조회돼도_상태는_한_번만_보고한다() {
        // given
        expectToken("token-1");
        expectTrack("token-1", trackResponse("IN_TRANSIT"));
        expectTrack("token-1", trackResponse("IN_TRANSIT"));

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);

        // then
        assertThat(publishedEvents).containsExactly(new CarrierStatusReportedEvent(1L, "IN_TRANSIT", TIME));
    }

    @Test
    void 같은_상태라도_시각이_다르면_다시_보고한다() {
        // given
        expectToken("token-1");
        expectTrack("token-1", trackResponse("IN_TRANSIT", "2026-11-08T10:49:26.000+09:00"));
        expectTrack("token-1", trackResponse("IN_TRANSIT", "2026-11-08T18:00:00.000+09:00"));

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);

        // then
        assertThat(publishedEvents).containsExactly(
                new CarrierStatusReportedEvent(1L, "IN_TRANSIT", "2026-11-08T10:49:26.000+09:00"),
                new CarrierStatusReportedEvent(1L, "IN_TRANSIT", "2026-11-08T18:00:00.000+09:00"));
    }

    @Test
    void 시각이_비어_오면_키의_시각_자리는_대시다() {
        // given
        expectToken("token-1");
        expectTrack("token-1", "{\"data\":{\"track\":{\"lastEvent\":{\"time\":null,\"status\":{\"code\":\"IN_TRANSIT\"}}}}}");

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);

        // then
        assertThat(publishedEvents).hasSize(1);
        assertThat(((CarrierStatusReportedEvent) publishedEvents.get(0)).eventKey()).isEqualTo("IN_TRANSIT:-");
    }

    static Stream<String> 배송_완료가_아닌_상태() {
        return Stream.of("INFORMATION_RECEIVED", "AT_PICKUP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "ATTEMPT_FAIL", "EXCEPTION", "UNKNOWN");
    }

    @Test
    void 이벤트가_아직_없으면_알리지_않는다() {
        // given
        expectToken("token-1");
        expectTrack("token-1", "{\"data\":{\"track\":{\"lastEvent\":null}}}");

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);

        // then
        assertThat(publishedEvents).isEmpty();
    }

    // 실패 처리

    @Test
    void 송장을_찾을_수_없다는_오류_응답이면_알리지_않고_예외도_던지지_않는다() {
        // given
        expectToken("token-1");
        expectTrack("token-1", "{\"data\":null,\"errors\":[{\"message\":\"\",\"extensions\":{\"code\":\"NOT_FOUND\"}}]}");

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);

        // then
        assertThat(publishedEvents).isEmpty();
    }

    @Test
    void 서버_오류이면_알리지_않고_예외도_던지지_않는다() {
        // given
        expectToken("token-1");
        server.expect(requestTo(GRAPHQL_URL)).andRespond(withServerError());

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);

        // then
        assertThat(publishedEvents).isEmpty();
    }

    @Test
    void 배송_완료를_반영하다_예외가_나도_밖으로_던지지_않는다() {
        // given
        ApplicationEventPublisher failingPublisher = event -> {
            throw new IllegalStateException("반영 실패");
        };
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        TrackerProperties properties = new TrackerProperties(GRAPHQL_URL, AUTH_URL, "client-id", "client-secret",
                Duration.ofHours(1), MAX_TRACKING_PERIOD, Duration.ofSeconds(1), Duration.ofSeconds(1));
        TrackerCarrierClient failing = new TrackerCarrierClient(builder.build(), properties, failingPublisher, CLOCK);
        expectToken("token-1");
        expectTrack("token-1", trackResponse("DELIVERED"));

        // when & then
        failing.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);
        failing.destroy();
    }

    // 토큰

    @Test
    void 토큰이_만료되면_새로_받아_한_번_다시_조회한다() {
        // given
        expectToken("expired");
        expectTrack("expired", unauthenticatedResponse());
        expectToken("fresh");
        expectTrack("fresh", trackResponse("DELIVERED"));

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);

        // then
        assertThat(publishedEvents).containsExactly(new CarrierDeliveredEvent(1L));
        server.verify();
    }

    @Test
    void 새_토큰으로도_인증이_거부되면_더_재시도하지_않고_알리지_않는다() {
        // given
        expectToken("expired");
        expectTrack("expired", unauthenticatedResponse());
        expectToken("still-bad");
        expectTrack("still-bad", unauthenticatedResponse());

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);

        // then
        assertThat(publishedEvents).isEmpty();
        server.verify();
    }

    @Test
    void 토큰은_한_번_받으면_다음_조회에서_다시_받지_않는다() {
        // given
        expectToken("token-1");
        expectTrack("token-1", trackResponse("IN_TRANSIT"));
        expectTrack("token-1", trackResponse("DELIVERED"));

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);

        // then
        assertThat(publishedEvents).containsExactly(
                new CarrierStatusReportedEvent(1L, "IN_TRANSIT", TIME), new CarrierDeliveredEvent(1L));
        server.verify();
    }

    @Test
    void 토큰은_client_credentials로_발급받는다() {
        // given
        server.expect(requestTo(AUTH_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(org.springframework.util.CollectionUtils.toMultiValueMap(java.util.Map.of(
                        "grant_type", List.of("client_credentials"),
                        "client_id", List.of("client-id"),
                        "client_secret", List.of("client-secret")))))
                .andRespond(withSuccess("{\"access_token\":\"token-1\",\"expires_in\":3600,\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));
        expectTrack("token-1", trackResponse("IN_TRANSIT"));

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);

        // then
        server.verify();
    }

    // 조회 예약

    @Test
    void 추적을_요청하면_주기마다_조회하다_배송_완료가_되면_알리고_멈춘다() throws Exception {
        // given
        sut.destroy();
        sut = newClient(Duration.ofMillis(100));
        AtomicInteger trackCalls = new AtomicInteger();
        server.expect(ExpectedCount.manyTimes(), requestTo(AUTH_URL)).andRespond(
                withSuccess("{\"access_token\":\"token-1\"}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.manyTimes(), requestTo(GRAPHQL_URL)).andRespond(request -> {
            String status = trackCalls.incrementAndGet() < 3 ? "IN_TRANSIT" : "DELIVERED";
            return withSuccess(trackResponse(status), MediaType.APPLICATION_JSON).createResponse(request);
        });

        // when
        sut.requestTracking(1L, "POST_OFFICE", TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);
        Thread.sleep(700);

        // then
        assertThat(publishedEvents).containsExactly(
                new CarrierStatusReportedEvent(1L, "IN_TRANSIT", TIME), new CarrierDeliveredEvent(1L));
        assertThat(trackCalls.get()).isEqualTo(3);
    }

    @Test
    void 같은_shipment를_두_번_요청해도_조회는_하나만_돈다() throws Exception {
        // given
        sut.destroy();
        sut = newClient(Duration.ofMillis(200));
        AtomicInteger trackCalls = new AtomicInteger();
        server.expect(ExpectedCount.manyTimes(), requestTo(AUTH_URL)).andRespond(
                withSuccess("{\"access_token\":\"token-1\"}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.manyTimes(), requestTo(GRAPHQL_URL)).andRespond(request -> {
            trackCalls.incrementAndGet();
            return withSuccess(trackResponse("IN_TRANSIT"), MediaType.APPLICATION_JSON).createResponse(request);
        });

        // when
        sut.requestTracking(1L, "POST_OFFICE", TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);
        sut.requestTracking(1L, "POST_OFFICE", TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);
        Thread.sleep(500);

        // then
        assertThat(trackCalls.get()).isLessThanOrEqualTo(3);
    }

    // 조회 상한

    @Test
    void 출고_후_기간_안이면_계속_조회한다() {
        // given
        expectToken("token-1");
        expectTrack("token-1", trackResponse("IN_TRANSIT"));
        expectTrack("token-1", trackResponse("IN_TRANSIT"));
        LocalDateTime exactlyAtLimit = NOW.minus(MAX_TRACKING_PERIOD);

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, exactlyAtLimit);
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);

        // then
        server.verify();
        assertThat(publishedEvents).noneMatch(event -> event instanceof CarrierTrackingGaveUpEvent);
    }

    @Test
    void 출고_후_기간이_지나면_조회_없이_멈추고_포기를_한_번만_알린다() throws Exception {
        // given
        sut.destroy();
        sut = newClient(Duration.ofMillis(100));

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_OVER_LIMIT);
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_OVER_LIMIT);

        // then
        server.verify();
        assertThat(publishedEvents).containsExactly(
                new CarrierTrackingGaveUpEvent(1L, SHIPPED_OVER_LIMIT, MAX_TRACKING_PERIOD));
    }

    @Test
    void 요청_시점에_이미_기간이_지났으면_조회를_예약하지_않고_바로_포기를_알린다() {
        // when
        sut.requestTracking(1L, "POST_OFFICE", TRACKING_NUMBER, SHIPPED_OVER_LIMIT);
        sut.requestTracking(1L, "POST_OFFICE", TRACKING_NUMBER, SHIPPED_OVER_LIMIT);

        // then
        // 예약하지 않았다는 것은 기다려서 요청이 없음을 보는 대신 예약 상태로 직접 확인한다
        assertThat(sut.isPolling(1L)).isFalse();
        server.verify();
        assertThat(publishedEvents).containsExactly(
                new CarrierTrackingGaveUpEvent(1L, SHIPPED_OVER_LIMIT, MAX_TRACKING_PERIOD));
    }

    @Test
    void 예약된_조회가_상한을_넘으면_조회를_멈추고_포기를_한_번만_알린다() {
        // given
        MutableClock clock = new MutableClock(NOW);
        sut.destroy();
        sut = newClient(Duration.ofMillis(50), clock);
        AtomicInteger trackCalls = new AtomicInteger();
        server.expect(ExpectedCount.manyTimes(), requestTo(AUTH_URL)).andRespond(
                withSuccess("{\"access_token\":\"token-1\"}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.manyTimes(), requestTo(GRAPHQL_URL)).andRespond(request -> {
            trackCalls.incrementAndGet();
            return withSuccess(trackResponse("IN_TRANSIT"), MediaType.APPLICATION_JSON).createResponse(request);
        });
        LocalDateTime exactlyAtLimit = NOW.minus(MAX_TRACKING_PERIOD);
        sut.requestTracking(1L, "POST_OFFICE", TRACKING_NUMBER, exactlyAtLimit);
        await().atMost(Duration.ofSeconds(3)).until(() -> trackCalls.get() >= 1);

        // when
        clock.advance(Duration.ofSeconds(1));

        // then
        await().atMost(Duration.ofSeconds(3)).until(() -> !sut.isPolling(1L));
        assertThat(publishedEvents).filteredOn(event -> event instanceof CarrierTrackingGaveUpEvent)
                .containsExactly(new CarrierTrackingGaveUpEvent(1L, exactlyAtLimit, MAX_TRACKING_PERIOD));
    }

    @Test
    void 오류_응답은_상태를_보고하지_않고_기간이_지나면_상한이_적용된다() {
        // given
        expectToken("token-1");
        expectTrack("token-1", "{\"data\":null,\"errors\":[{\"message\":\"\",\"extensions\":{\"code\":\"NOT_FOUND\"}}]}");

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT);
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER, SHIPPED_OVER_LIMIT);

        // then
        server.verify();
        assertThat(publishedEvents).containsExactly(
                new CarrierTrackingGaveUpEvent(1L, SHIPPED_OVER_LIMIT, MAX_TRACKING_PERIOD));
    }

    // 택배사 매핑

    @ParameterizedTest
    @MethodSource("택배사_매핑")
    void 택배사를_Delivery_Tracker_carrierId로_바꾼다(Carrier carrier, String expectedCarrierId) {
        // when
        String carrierId = TrackerCarrierClient.trackerCarrierId(carrier);

        // then
        assertThat(carrierId).isEqualTo(expectedCarrierId);
    }

    static Stream<Object[]> 택배사_매핑() {
        return Stream.of(
                new Object[]{Carrier.CJ_LOGISTICS, "kr.cjlogistics"},
                new Object[]{Carrier.POST_OFFICE, "kr.epost"},
                new Object[]{Carrier.HANJIN, "kr.hanjin"},
                new Object[]{Carrier.LOTTE, "kr.lotte"},
                new Object[]{Carrier.LOGEN, "kr.logen"});
    }

    @Test
    void 알_수_없는_택배사_코드로_추적을_요청하면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> sut.requestTracking(1L, "UNKNOWN_CARRIER", TRACKING_NUMBER, SHIPPED_WITHIN_LIMIT))
                .isInstanceOf(com.ok.common.exception.RestApiException.class);
    }

    // 설정

    @Test
    void 설정을_문자열로_바꿀_때_client_secret을_가린다() {
        // given
        TrackerProperties sut = new TrackerProperties(GRAPHQL_URL, AUTH_URL, "client-id", "super-secret",
                Duration.ofMinutes(5), MAX_TRACKING_PERIOD, Duration.ofSeconds(3), Duration.ofSeconds(15));

        // when
        String text = sut.toString();

        // then
        assertThat(text).doesNotContain("super-secret").contains("clientSecret=****", "maxTrackingPeriod=PT168H");
    }

    @ParameterizedTest
    @MethodSource("올바르지_않은_조회_상한")
    void 조회_상한이_비었거나_0_이하이면_만들_수_없다(Duration maxTrackingPeriod) {
        // when & then
        assertThatThrownBy(() -> new TrackerProperties(GRAPHQL_URL, AUTH_URL, "client-id", "client-secret",
                Duration.ofMinutes(5), maxTrackingPeriod, Duration.ofSeconds(3), Duration.ofSeconds(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Duration> 올바르지_않은_조회_상한() {
        return Stream.of(null, Duration.ZERO, Duration.ofDays(-1));
    }

    @ParameterizedTest
    @MethodSource("비어_있는_인증_정보")
    void 인증_정보가_비어_있으면_만들_수_없다(String clientId, String clientSecret) {
        // when & then
        assertThatThrownBy(() -> new TrackerProperties(GRAPHQL_URL, AUTH_URL, clientId, clientSecret,
                Duration.ofMinutes(5), MAX_TRACKING_PERIOD, Duration.ofSeconds(3), Duration.ofSeconds(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Object[]> 비어_있는_인증_정보() {
        return Stream.of(
                new Object[]{null, "secret"},
                new Object[]{"", "secret"},
                new Object[]{" ", "secret"},
                new Object[]{"id", null},
                new Object[]{"id", ""},
                new Object[]{"id", " "});
    }

    // 헬퍼

    private void expectToken(String accessToken) {
        server.expect(ExpectedCount.once(), requestTo(AUTH_URL))
                .andRespond(withSuccess("{\"access_token\":\"" + accessToken + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));
    }

    private void expectTrack(String accessToken, String responseBody) {
        server.expect(ExpectedCount.once(), requestTo(GRAPHQL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + accessToken))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
    }

    private static String trackResponse(String statusCode) {
        return trackResponse(statusCode, TIME);
    }

    private static String trackResponse(String statusCode, String time) {
        return "{\"data\":{\"track\":{\"lastEvent\":{\"time\":\"" + time + "\",\"status\":{\"code\":\"" + statusCode + "\"}}}}}";
    }

    private static String unauthenticatedResponse() {
        return "{\"errors\":[{\"message\":\"Invalid or expired token.\",\"extensions\":{\"code\":\"UNAUTHENTICATED\"}}]}";
    }

    // 예약된 조회가 도는 도중에 시각을 옮기려고 쓴다. 조회는 다른 스레드에서 돌아 값을 AtomicReference로 둔다
    private static class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        MutableClock(LocalDateTime start) {
            this.now = new AtomicReference<>(start.atZone(ZONE).toInstant());
        }

        void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
