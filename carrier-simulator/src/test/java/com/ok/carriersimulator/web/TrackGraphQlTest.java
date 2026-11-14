package com.ok.carriersimulator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.FaultSettings;
import com.ok.carriersimulator.core.ParcelDetail;
import com.ok.carriersimulator.core.ParcelKey;
import com.ok.carriersimulator.testsupport.MutableClock;
import com.ok.carriersimulator.testsupport.SimulatorClient;
import com.ok.carriersimulator.testsupport.SimulatorClient.HttpResult;
import com.ok.carriersimulator.testsupport.SimulatorWebTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;

@SimulatorWebTest
class TrackGraphQlTest {

    // delivery/src/main/java/com/ok/delivery/adapter/out/carrier/TrackerCarrierClient.java의 TRACK_QUERY를 그대로 옮겼다.
    // delivery가 실제로 보내는 조회가 이 서버에서 동작하는지 확인하는 것이 목적이라, 그쪽이 바뀌면 함께 바꾼다
    private static final String TRACK_QUERY = """
            query Track($carrierId: ID!, $trackingNumber: String!) {
              track(carrierId: $carrierId, trackingNumber: $trackingNumber) {
                lastEvent { status { code } }
              }
            }
            """;
    private static final String EVENTS_QUERY = """
            query Track($carrierId: ID!, $trackingNumber: String!) {
              track(carrierId: $carrierId, trackingNumber: $trackingNumber) {
                trackingNumber
                events(last: 2) {
                  edges { node { status { code name } time location { name } description } cursor }
                  pageInfo { hasPreviousPage hasNextPage startCursor endCursor }
                }
              }
            }
            """;
    private static final String EVENTS_AFTER_QUERY = """
            query Track($carrierId: ID!, $trackingNumber: String!, $after: String) {
              track(carrierId: $carrierId, trackingNumber: $trackingNumber) {
                events(after: $after) { edges { cursor } }
              }
            }
            """;
    private static final String REGISTER_MUTATION = """
            mutation Register($input: RegisterTrackWebhookInput!) {
              registerTrackWebhook(input: $input)
            }
            """;
    private static final String CARRIER = "kr.cjlogistics";
    private static final String NORMAL = "901000000001";

    @Autowired
    private SimulatorClient client;
    @Autowired
    private CarrierSimulator simulator;
    @Autowired
    private MutableClock clock;

    private static Map<String, Object> variables(String trackingNumber) {
        return Map.of("carrierId", CARRIER, "trackingNumber", trackingNumber);
    }

    private static Map<String, Object> registerInput(String callbackUrl, String expirationTime) {
        return Map.of("input", Map.of("carrierId", CARRIER, "trackingNumber", NORMAL,
                "callbackUrl", callbackUrl, "expirationTime", expirationTime));
    }

    @Test
    void delivery와_같은_조회로_마지막_상태를_받는다() {
        // given
        String token = client.token();

        // when
        HttpResult sut = client.graphql(TRACK_QUERY, variables(NORMAL), token);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(200);
            softly.assertThat(sut.<String>read("$.data.track.lastEvent.status.code")).isEqualTo("INFORMATION_RECEIVED");
            softly.assertThat(sut.body()).doesNotContain("\"errors\"");
        });
    }

    @Test
    void 시간이_지나면_배송_완료가_된다() {
        // given
        String token = client.token();
        client.graphql(TRACK_QUERY, variables(NORMAL), token);

        // when
        clock.advance(Duration.ofSeconds(30));
        HttpResult sut = client.graphql(TRACK_QUERY, variables(NORMAL), token);

        // then
        assertThat(sut.<String>read("$.data.track.lastEvent.status.code")).isEqualTo("DELIVERED");
    }

    @ParameterizedTest
    @MethodSource("쓸_수_없는_토큰")
    void 토큰이_없거나_틀리면_본문에_UNAUTHENTICATED가_온다(String token) {
        // when
        HttpResult sut = client.graphql(TRACK_QUERY, variables(NORMAL), token);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(200);
            softly.assertThat(sut.<String>read("$.errors[0].extensions.code")).isEqualTo("UNAUTHENTICATED");
        });
    }

    static Stream<String> 쓸_수_없는_토큰() {
        return Stream.of(null, "", "wrong-token");
    }

    @Test
    void 만료시킨_토큰으로_조회하면_UNAUTHENTICATED가_오고_새_토큰은_된다() {
        // given
        String expired = client.token();
        simulator.expireTokens();

        // when
        HttpResult sut = client.graphql(TRACK_QUERY, variables(NORMAL), expired);
        HttpResult retried = client.graphql(TRACK_QUERY, variables(NORMAL), client.token());

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.<String>read("$.errors[0].extensions.code")).isEqualTo("UNAUTHENTICATED");
            softly.assertThat(retried.<String>read("$.data.track.lastEvent.status.code"))
                    .isEqualTo("INFORMATION_RECEIVED");
        });
    }

    @Test
    void 없는_송장은_NOT_FOUND다() {
        // when
        HttpResult sut = client.graphql(TRACK_QUERY, variables("904000000001"), client.token());

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(200);
            softly.assertThat(sut.<String>read("$.errors[0].extensions.code")).isEqualTo("NOT_FOUND");
            softly.assertThat(sut.<Object>read("$.data.track")).isNull();
        });
    }

    @ParameterizedTest
    @MethodSource("빈_송장_번호")
    void 송장_번호가_비면_BAD_REQUEST다(String trackingNumber) {
        // when
        HttpResult sut = client.graphql(TRACK_QUERY, variables(trackingNumber), client.token());

        // then
        assertThat(sut.<String>read("$.errors[0].extensions.code")).isEqualTo("BAD_REQUEST");
    }

    static Stream<String> 빈_송장_번호() {
        return Stream.of("", " ");
    }

    @Test
    void 최근_사건_두_개만_받는다() {
        // given
        String token = client.token();
        client.graphql(TRACK_QUERY, variables(NORMAL), token);
        clock.advance(Duration.ofSeconds(10));

        // when: 10초 뒤에는 접수, 집화, 이동 중 세 사건이 있다
        HttpResult sut = client.graphql(EVENTS_QUERY, variables(NORMAL), token);

        // then
        List<String> codes = sut.read("$.data.track.events.edges[*].node.status.code");
        assertSoftly(softly -> {
            softly.assertThat(codes).containsExactly("AT_PICKUP", "IN_TRANSIT");
            softly.assertThat(sut.<String>read("$.data.track.trackingNumber")).isEqualTo(NORMAL);
            softly.assertThat(sut.<String>read("$.data.track.events.edges[1].node.status.name")).isEqualTo("이동 중");
            softly.assertThat(sut.<String>read("$.data.track.events.edges[1].node.location.name")).isEqualTo("옥천HUB");
            softly.assertThat(sut.<String>read("$.data.track.events.edges[1].cursor")).isEqualTo("2");
            softly.assertThat(sut.<Boolean>read("$.data.track.events.pageInfo.hasPreviousPage")).isTrue();
            softly.assertThat(sut.<Boolean>read("$.data.track.events.pageInfo.hasNextPage")).isFalse();
            softly.assertThat(sut.<String>read("$.data.track.events.pageInfo.startCursor")).isEqualTo("1");
            // 사건 시각은 시간대를 포함한 RFC3339 문자열이다
            softly.assertThat(OffsetDateTime.parse(sut.<String>read("$.data.track.events.edges[1].node.time")).toInstant())
                    .isEqualTo(clock.instant());
        });
    }

    @Test
    void 알림을_등록하면_true를_받고_등록이_남는다() {
        // given
        String expiration = clock.instant().plus(Duration.ofHours(48)).toString();

        // when
        HttpResult sut = client.graphql(REGISTER_MUTATION,
                registerInput("http://localhost:8080/carrier-callback", expiration), client.token());

        // then
        ParcelDetail detail = simulator.parcel(new ParcelKey(CARRIER, NORMAL), clock.instant()).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(sut.<Boolean>read("$.data.registerTrackWebhook")).isTrue();
            softly.assertThat(detail.registration()).isNotNull();
            softly.assertThat(detail.registration().callbackUrl()).isEqualTo("http://localhost:8080/carrier-callback");
            softly.assertThat(detail.registration().expirationTime())
                    .isEqualTo(clock.instant().plus(Duration.ofHours(48)));
        });
    }

    @Test
    void 만료_시각에_시간대가_있어도_같은_순간으로_읽는다() {
        // given: 한국 시간으로 적은 만료 시각
        String expiration = clock.instant().plus(Duration.ofHours(48)).atOffset(java.time.ZoneOffset.ofHours(9)).toString();

        // when
        client.graphql(REGISTER_MUTATION, registerInput("https://example.test/callback", expiration), client.token());

        // then
        ParcelDetail detail = simulator.parcel(new ParcelKey(CARRIER, NORMAL), clock.instant()).orElseThrow();
        assertThat(detail.registration().expirationTime()).isEqualTo(clock.instant().plus(Duration.ofHours(48)));
    }

    @ParameterizedTest
    @MethodSource("http가_아닌_주소")
    void callbackUrl이_http가_아니면_BAD_REQUEST다(String callbackUrl) {
        // given
        String expiration = clock.instant().plus(Duration.ofHours(48)).toString();

        // when
        HttpResult sut = client.graphql(REGISTER_MUTATION, registerInput(callbackUrl, expiration), client.token());

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.<String>read("$.errors[0].extensions.code")).isEqualTo("BAD_REQUEST");
            softly.assertThat(simulator.parcel(new ParcelKey(CARRIER, NORMAL), clock.instant())).isEmpty();
        });
    }

    static Stream<String> http가_아닌_주소() {
        return Stream.of("", "ftp://example.test/callback", "localhost:8080/callback", "javascript:alert(1)");
    }

    @ParameterizedTest
    @MethodSource("틀린_만료_시각")
    void 만료_시각_형식이_틀리면_오류가_온다(String expirationTime) {
        // when
        HttpResult sut = client.graphql(REGISTER_MUTATION,
                registerInput("http://localhost:8080/callback", expirationTime), client.token());

        // then
        List<Object> errors = sut.read("$.errors");
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(200);
            softly.assertThat(errors).isNotEmpty();
            softly.assertThat(simulator.parcel(new ParcelKey(CARRIER, NORMAL), clock.instant())).isEmpty();
        });
    }

    static Stream<String> 틀린_만료_시각() {
        return Stream.of("내일", "2026-11-16", "2026-11-16T00:00:00");
    }

    @Test
    void 지난_만료_시각으로_등록하면_등록이_지워진다() {
        // given
        String token = client.token();
        client.graphql(REGISTER_MUTATION, registerInput("http://localhost:8080/callback",
                clock.instant().plus(Duration.ofHours(48)).toString()), token);

        // when
        HttpResult sut = client.graphql(REGISTER_MUTATION, registerInput("http://localhost:8080/callback",
                clock.instant().toString()), token);

        // then
        ParcelDetail detail = simulator.parcel(new ParcelKey(CARRIER, NORMAL), clock.instant()).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(sut.<Boolean>read("$.data.registerTrackWebhook")).isTrue();
            softly.assertThat(detail.registration()).isNull();
        });
    }

    @ParameterizedTest
    @MethodSource("내보낸_적_없는_cursor")
    void 내보낸_적_없는_cursor를_주면_BAD_REQUEST가_온다(String cursor) {
        // given
        String token = client.token();
        Map<String, Object> variables = Map.of("carrierId", CARRIER, "trackingNumber", NORMAL, "after", cursor);

        // when
        HttpResult sut = client.graphql(EVENTS_AFTER_QUERY, variables, token);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(200);
            softly.assertThat(sut.<String>read("$.errors[0].extensions.code")).isEqualTo("BAD_REQUEST");
            softly.assertThat(sut.<String>read("$.errors[0].message")).isEqualTo("알 수 없는 cursor입니다.");
        });
    }

    static Stream<String> 내보낸_적_없는_cursor() {
        return Stream.of("-5", "2147483647", "9999999999");
    }

    @Test
    void 오류_비율을_100으로_두면_INTERNAL이_온다() {
        // given
        String token = client.token();
        simulator.updateFaults(new FaultSettings(0, 100, 1, false));

        // when
        HttpResult sut = client.graphql(TRACK_QUERY, variables(NORMAL), token);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(200);
            softly.assertThat(sut.<String>read("$.errors[0].extensions.code")).isEqualTo("INTERNAL");
        });
    }

    @Test
    void 응답_지연이_몰려도_관리_화면은_지연된_요청이_끝나기_전에_열린다() throws Exception {
        // given: 응답을 3초 늦추고 조회 250건을 한꺼번에 보낸다
        String token = client.token();
        simulator.updateFaults(new FaultSettings(3000, 0, 1, false));
        AtomicInteger finished = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 250; i++) {
                executor.submit(() -> {
                    client.graphql(TRACK_QUERY, variables(NORMAL), token);
                    finished.incrementAndGet();
                });
            }
            Thread.sleep(700);

            // when: 지연을 끄려고 관리 화면을 연다
            int status = RestClient.create().get()
                    .uri(client.baseUrl() + "/settings")
                    .exchange((request, response) -> response.getStatusCode().value());
            int finishedWhenOpened = finished.get();

            // then: 걸린 시간을 재지 않고 순서로 확인한다. 화면이 막혔다면 지연된 요청이 끝난 뒤에야 열린다
            assertSoftly(softly -> {
                softly.assertThat(status).isEqualTo(200);
                softly.assertThat(finishedWhenOpened).isZero();
            });
        }
    }

    @Test
    void 응답_지연을_주면_그만큼_늦게_답한다() {
        // given
        String token = client.token();
        simulator.updateFaults(new FaultSettings(300, 0, 1, false));

        // when
        long startedAt = System.nanoTime();
        HttpResult sut = client.graphql(TRACK_QUERY, variables(NORMAL), token);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        // then
        assertSoftly(softly -> {
            softly.assertThat(elapsedMillis).isGreaterThanOrEqualTo(300);
            softly.assertThat(sut.<String>read("$.data.track.lastEvent.status.code")).isEqualTo("INFORMATION_RECEIVED");
        });
    }
}
