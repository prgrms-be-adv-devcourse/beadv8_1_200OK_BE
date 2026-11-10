package com.ok.delivery.adapter.out.carrier;

import com.ok.delivery.adapter.in.event.CarrierDeliveredEvent;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private final List<Object> publishedEvents = new ArrayList<>();
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
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(false).build();
        TrackerProperties properties = new TrackerProperties(GRAPHQL_URL, AUTH_URL, "client-id", "client-secret",
                pollInterval, Duration.ofSeconds(1), Duration.ofSeconds(1));
        ApplicationEventPublisher publisher = publishedEvents::add;
        return new TrackerCarrierClient(builder.build(), properties, publisher);
    }

    // 상태 판단

    @Test
    void 마지막_상태가_배송_완료이면_배송_완료를_알린다() {
        // given
        expectToken("token-1");
        expectTrack("token-1", trackResponse("DELIVERED"));

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER);

        // then
        assertThat(publishedEvents).containsExactly(new CarrierDeliveredEvent(1L));
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("배송_완료가_아닌_상태")
    void 배송_완료가_아닌_상태이면_알리지_않는다(String statusCode) {
        // given
        expectToken("token-1");
        expectTrack("token-1", trackResponse(statusCode));

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER);

        // then
        assertThat(publishedEvents).isEmpty();
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
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER);

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
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER);

        // then
        assertThat(publishedEvents).isEmpty();
    }

    @Test
    void 서버_오류이면_알리지_않고_예외도_던지지_않는다() {
        // given
        expectToken("token-1");
        server.expect(requestTo(GRAPHQL_URL)).andRespond(withServerError());

        // when
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER);

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
                Duration.ofHours(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        TrackerCarrierClient failing = new TrackerCarrierClient(builder.build(), properties, failingPublisher);
        expectToken("token-1");
        expectTrack("token-1", trackResponse("DELIVERED"));

        // when & then
        failing.poll(1L, CARRIER_ID, TRACKING_NUMBER);
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
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER);

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
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER);

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
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER);
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER);

        // then
        assertThat(publishedEvents).containsExactly(new CarrierDeliveredEvent(1L));
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
        sut.poll(1L, CARRIER_ID, TRACKING_NUMBER);

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
        sut.requestTracking(1L, "POST_OFFICE", TRACKING_NUMBER);
        Thread.sleep(700);

        // then
        assertThat(publishedEvents).containsExactly(new CarrierDeliveredEvent(1L));
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
        sut.requestTracking(1L, "POST_OFFICE", TRACKING_NUMBER);
        sut.requestTracking(1L, "POST_OFFICE", TRACKING_NUMBER);
        Thread.sleep(500);

        // then
        assertThat(trackCalls.get()).isLessThanOrEqualTo(3);
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
        assertThatThrownBy(() -> sut.requestTracking(1L, "UNKNOWN_CARRIER", TRACKING_NUMBER))
                .isInstanceOf(com.ok.common.exception.RestApiException.class);
    }

    // 설정

    @Test
    void 설정을_문자열로_바꿀_때_client_secret을_가린다() {
        // given
        TrackerProperties sut = new TrackerProperties(GRAPHQL_URL, AUTH_URL, "client-id", "super-secret",
                Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(15));

        // when
        String text = sut.toString();

        // then
        assertThat(text).doesNotContain("super-secret").contains("clientSecret=****");
    }

    @ParameterizedTest
    @MethodSource("비어_있는_인증_정보")
    void 인증_정보가_비어_있으면_만들_수_없다(String clientId, String clientSecret) {
        // when & then
        assertThatThrownBy(() -> new TrackerProperties(GRAPHQL_URL, AUTH_URL, clientId, clientSecret,
                Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofSeconds(15)))
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
        return "{\"data\":{\"track\":{\"lastEvent\":{\"time\":\"2026-11-08T10:49:26.000+09:00\",\"status\":{\"code\":\"" + statusCode + "\"}}}}}";
    }

    private static String unauthenticatedResponse() {
        return "{\"errors\":[{\"message\":\"Invalid or expired token.\",\"extensions\":{\"code\":\"UNAUTHENTICATED\"}}]}";
    }
}
