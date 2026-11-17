package com.ok.delivery.adapter.out.carrier;

import com.ok.delivery.domain.Carrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 알림 등록(#668)과 알림 수신 쪽이 쓰는 택배사 식별자 변환·배송 완료 확인을 확인한다. 응답은 실제 API 모양을 흉내 낸다
class TrackerCarrierClientWebhookTest {

    private static final String GRAPHQL_URL = "https://tracker.test/graphql";
    private static final String AUTH_URL = "https://auth.tracker.test/oauth2/token";
    private static final String CALLBACK_URL = "https://delivery.test/api/v1/carrier-notifications/s3cret";
    private static final String TRACKING_NUMBER = "1234567890123";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), ZoneOffset.UTC);

    private MockRestServiceServer server;
    private TrackerCarrierClient sut;

    @AfterEach
    void tearDown() {
        sut.destroy();
    }

    @Test
    void 알림_등록은_택배사_식별자와_송장_callbackUrl_만료_시각을_보낸다() {
        // given
        sut = newClient(CALLBACK_URL);
        expectToken();
        server.expect(ExpectedCount.once(), requestTo(GRAPHQL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("registerTrackWebhook")))
                .andExpect(jsonPath("$.variables.input.carrierId").value("kr.cjlogistics"))
                .andExpect(jsonPath("$.variables.input.trackingNumber").value(TRACKING_NUMBER))
                .andExpect(jsonPath("$.variables.input.callbackUrl").value(CALLBACK_URL))
                .andExpect(jsonPath("$.variables.input.expirationTime").value("2026-11-18T01:00Z"))
                .andRespond(withSuccess("{\"data\":{\"registerTrackWebhook\":true}}", MediaType.APPLICATION_JSON));

        // when
        sut.registerWebhook("kr.cjlogistics", TRACKING_NUMBER);

        // then
        server.verify();
    }

    @Test
    void 알림_등록이_오류_응답이어도_예외를_던지지_않는다() {
        // given
        sut = newClient(CALLBACK_URL);
        expectToken();
        server.expect(requestTo(GRAPHQL_URL)).andRespond(withSuccess(
                "{\"data\":null,\"errors\":[{\"message\":\"bad\",\"extensions\":{\"code\":\"BAD_REQUEST\"}}]}", MediaType.APPLICATION_JSON));

        // when & then
        sut.registerWebhook("kr.cjlogistics", TRACKING_NUMBER);
        server.verify();
    }

    @Test
    void 알림_등록이_서버_오류여도_예외를_던지지_않는다() {
        // given
        sut = newClient(CALLBACK_URL);
        expectToken();
        server.expect(requestTo(GRAPHQL_URL)).andRespond(withServerError());

        // when & then
        sut.registerWebhook("kr.cjlogistics", TRACKING_NUMBER);
        server.verify();
    }

    @Test
    void 추적을_요청하면_알림을_등록한다() {
        // given
        sut = newClient(CALLBACK_URL);
        expectToken();
        server.expect(ExpectedCount.once(), requestTo(GRAPHQL_URL))
                .andExpect(content().string(containsString("registerTrackWebhook")))
                .andRespond(withSuccess("{\"data\":{\"registerTrackWebhook\":true}}", MediaType.APPLICATION_JSON));

        // when
        sut.requestTracking(1L, "CJ_LOGISTICS", TRACKING_NUMBER);

        // then
        server.verify(Duration.ofSeconds(3));
    }

    @Test
    void 같은_shipment를_두_번_요청해도_알림은_한_번만_등록한다() {
        // given
        sut = newClient(CALLBACK_URL);
        expectToken();
        server.expect(ExpectedCount.once(), requestTo(GRAPHQL_URL))
                .andRespond(withSuccess("{\"data\":{\"registerTrackWebhook\":true}}", MediaType.APPLICATION_JSON));

        // when
        sut.requestTracking(1L, "CJ_LOGISTICS", TRACKING_NUMBER);
        sut.requestTracking(1L, "CJ_LOGISTICS", TRACKING_NUMBER);

        // then
        server.verify(Duration.ofSeconds(3));
    }

    @ParameterizedTest
    @MethodSource("비어_있는_callbackUrl")
    void callbackUrl이_비어_있으면_알림을_등록하지_않는다(String callbackUrl) throws Exception {
        // given
        sut = newClient(callbackUrl);

        // when
        sut.requestTracking(1L, "CJ_LOGISTICS", TRACKING_NUMBER);
        Thread.sleep(300);

        // then
        server.verify();
    }

    static Stream<String> 비어_있는_callbackUrl() {
        return Stream.of(null, "", " ");
    }

    @Test
    void 알림_등록이_실패해도_추적_요청은_이어진다() throws Exception {
        // given
        sut = newClient(CALLBACK_URL);
        expectToken();
        server.expect(requestTo(GRAPHQL_URL)).andRespond(withServerError());

        // when
        sut.requestTracking(1L, "CJ_LOGISTICS", TRACKING_NUMBER);
        Thread.sleep(300);

        // then
        assertThat(sut.findCarrier("kr.cjlogistics")).contains(Carrier.CJ_LOGISTICS);
    }

    // 택배사 식별자 역매핑

    @ParameterizedTest
    @MethodSource("모든_택배사")
    void 택배사_식별자를_다시_택배사로_되돌린다(Carrier carrier) {
        // given
        sut = newClient(CALLBACK_URL);
        String carrierId = TrackerCarrierClient.trackerCarrierId(carrier);

        // when
        Optional<Carrier> found = sut.findCarrier(carrierId);

        // then
        assertThat(found).contains(carrier);
    }

    static Stream<Carrier> 모든_택배사() {
        return Stream.of(Carrier.values());
    }

    @ParameterizedTest
    @MethodSource("모르는_택배사_식별자")
    void 모르는_택배사_식별자는_비어_있다(String carrierId) {
        // given
        sut = newClient(CALLBACK_URL);

        // when & then
        assertThat(sut.findCarrier(carrierId)).isEmpty();
    }

    static Stream<String> 모르는_택배사_식별자() {
        return Stream.of("kr.unknown", "CJ_LOGISTICS", "", " ", null);
    }

    // 배송 완료 확인

    @Test
    void 택배사에_조회해_배송_완료이면_true를_돌려준다() {
        // given
        sut = newClient(CALLBACK_URL);
        expectToken();
        server.expect(requestTo(GRAPHQL_URL))
                .andExpect(jsonPath("$.variables.carrierId").value("kr.epost"))
                .andRespond(withSuccess(trackResponse("DELIVERED"), MediaType.APPLICATION_JSON));

        // when
        boolean delivered = sut.isDelivered(Carrier.POST_OFFICE, TRACKING_NUMBER);

        // then
        assertThat(delivered).isTrue();
    }

    @Test
    void 배송_완료가_아니면_false를_돌려준다() {
        // given
        sut = newClient(CALLBACK_URL);
        expectToken();
        server.expect(requestTo(GRAPHQL_URL))
                .andRespond(withSuccess(trackResponse("IN_TRANSIT"), MediaType.APPLICATION_JSON));

        // when
        boolean delivered = sut.isDelivered(Carrier.POST_OFFICE, TRACKING_NUMBER);

        // then
        assertThat(delivered).isFalse();
    }

    private TrackerCarrierClient newClient(String callbackUrl) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        TrackerProperties properties = new TrackerProperties(GRAPHQL_URL, AUTH_URL, "client-id", "client-secret",
                Duration.ofHours(1), callbackUrl, Duration.ofHours(48), Duration.ofSeconds(1), Duration.ofSeconds(1));
        return new TrackerCarrierClient(builder.build(), properties, event -> { }, CLOCK);
    }

    private void expectToken() {
        server.expect(ExpectedCount.once(), requestTo(AUTH_URL))
                .andRespond(withSuccess("{\"access_token\":\"token-1\"}", MediaType.APPLICATION_JSON));
    }

    private static String trackResponse(String statusCode) {
        return "{\"data\":{\"track\":{\"lastEvent\":{\"status\":{\"code\":\"" + statusCode + "\"}}}}}";
    }
}
