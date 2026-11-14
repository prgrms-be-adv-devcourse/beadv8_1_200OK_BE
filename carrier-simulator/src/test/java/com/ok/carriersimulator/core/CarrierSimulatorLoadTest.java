package com.ok.carriersimulator.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

// 재생 속도는 가상 1시간 = 실제 1초, 흔들림은 0이다
class CarrierSimulatorLoadTest {

    private static final Instant START = Instant.parse("2026-11-14T00:00:00Z");
    private static final String CARRIER = "kr.cjlogistics";
    private static final String URL = "http://localhost:8080/carrier-callback";

    private static CarrierSimulator newSimulator() {
        DispatchSettings dispatch = new DispatchSettings(Duration.ofSeconds(1), 100, 10, false);
        return new CarrierSimulator(new SimulatorSettings(1, 24, 0, Duration.ofHours(1), dispatch), START);
    }

    private static Instant after(long seconds) {
        return START.plusSeconds(seconds);
    }

    private static List<TrackingEvent> eventsOf(TrackResult result) {
        assertThat(result).isInstanceOf(TrackResult.Found.class);
        return ((TrackResult.Found) result).events();
    }

    @Test
    void 더미_송장은_고른_시나리오의_번호로_만들어진다() {
        // given
        CarrierSimulator simulator = newSimulator();

        // when
        List<ParcelKey> sut = simulator.createDummies(Scenario.STUCK, "kr.epost", 3, null, START);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).extracting(ParcelKey::trackingNumber)
                    .containsExactly("903000000001", "903000000002", "903000000003");
            softly.assertThat(sut).extracting(ParcelKey::carrierId).containsOnly("kr.epost");
            softly.assertThat(simulator.parcels(new ParcelFilter(null, Scenario.STUCK, null), 0, 50, START).total())
                    .isEqualTo(3);
        });
    }

    @Test
    void 더미_순번은_이어지고_모두_지우면_1로_돌아간다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.createDummies(Scenario.NORMAL, CARRIER, 2, null, START);

        // when
        List<ParcelKey> next = simulator.createDummies(Scenario.SLOW, CARRIER, 1, null, START);
        simulator.clearAll();
        List<ParcelKey> afterClear = simulator.createDummies(Scenario.SLOW, CARRIER, 1, null, START);

        // then
        assertSoftly(softly -> {
            softly.assertThat(next).extracting(ParcelKey::trackingNumber).containsExactly("902000000003");
            softly.assertThat(afterClear).extracting(ParcelKey::trackingNumber).containsExactly("902000000001");
        });
    }

    @Test
    void 이미_있는_번호는_건너뛰고_개수를_채운다() {
        // given: 누가 먼저 조회해 둔 번호
        CarrierSimulator simulator = newSimulator();
        simulator.track(new ParcelKey(CARRIER, "901000000002"), START);

        // when
        List<ParcelKey> sut = simulator.createDummies(Scenario.NORMAL, CARRIER, 2, null, START);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).extracting(ParcelKey::trackingNumber).containsExactly("901000000001", "901000000003");
            softly.assertThat(simulator.stats(START).parcelCount()).isEqualTo(3);
        });
    }

    @Test
    void 받는_곳을_주면_등록_알림이_개수만큼_쌓이고_상태가_바뀌면_또_나간다() {
        // given
        CarrierSimulator simulator = newSimulator();

        // when
        List<ParcelKey> keys = simulator.createDummies(Scenario.NORMAL, CARRIER, 5, " " + URL + " ", START);
        long pendingAtStart = simulator.stats(START).pendingCallbacks();
        List<CallbackAttempt> first = simulator.pollDueCallbacks(START);
        List<CallbackAttempt> onPickup = simulator.pollDueCallbacks(after(4));

        // then
        ParcelDetail detail = simulator.parcel(keys.getFirst(), after(4)).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(pendingAtStart).isEqualTo(5);
            softly.assertThat(first).hasSize(5);
            softly.assertThat(first).extracting(CallbackAttempt::callbackUrl).containsOnly(URL);
            softly.assertThat(onPickup).hasSize(5);
            softly.assertThat(detail.registration().expirationTime()).isEqualTo(START.plus(Duration.ofHours(48)));
            // 화면에서 만든 등록은 받은 등록 수에 넣지 않는다
            softly.assertThat(simulator.stats(START).total(Metric.REGISTER)).isZero();
        });
    }

    @ParameterizedTest
    @MethodSource("빈_주소")
    void 받는_곳이_없으면_알림_없이_송장만_생긴다(String callbackUrl) {
        // given
        CarrierSimulator simulator = newSimulator();

        // when
        List<ParcelKey> keys = simulator.createDummies(Scenario.NORMAL, CARRIER, 3, callbackUrl, START);

        // then
        assertSoftly(softly -> {
            softly.assertThat(simulator.stats(after(30)).pendingCallbacks()).isZero();
            softly.assertThat(simulator.pollDueCallbacks(after(30))).isEmpty();
            softly.assertThat(simulator.parcel(keys.getFirst(), after(30)).orElseThrow().registration()).isNull();
            softly.assertThat(simulator.stats(after(30)).statusCounts()).containsEntry("배송 완료", 3L);
        });
    }

    static Stream<String> 빈_주소() {
        return Stream.of(null, "", "  ");
    }

    @ParameterizedTest
    @MethodSource("잘못된_개수")
    void 개수가_범위를_벗어나면_예외다(int count) {
        // given
        CarrierSimulator sut = newSimulator();

        // when & then
        assertThatThrownBy(() -> sut.createDummies(Scenario.NORMAL, CARRIER, count, null, START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("더미 송장 개수는 1 이상 100,000 이하여야 합니다.");
    }

    static Stream<Integer> 잘못된_개수() {
        return Stream.of(0, -1, 100_001);
    }

    @Test
    void 지정한_상태가_마지막_상태로_남는다() {
        // given: 정상 송장이 집화(4초)까지 진행했다
        CarrierSimulator simulator = newSimulator();
        ParcelKey key = new ParcelKey(CARRIER, "901000000001");
        simulator.track(key, START);

        // when
        simulator.sendManual(List.of(key), StatusCode.DELIVERED, 1, URL, after(5));
        List<TrackingEvent> sut = eventsOf(simulator.track(key, after(100)));

        // then: 시나리오가 더 진행하지 않는다
        assertSoftly(softly -> {
            softly.assertThat(sut).extracting(TrackingEvent::status).containsExactly(
                    StatusCode.INFORMATION_RECEIVED, StatusCode.AT_PICKUP, StatusCode.DELIVERED);
            softly.assertThat(sut.getLast().time()).isEqualTo(after(5));
            softly.assertThat(sut.getLast().description()).isEqualTo("수동 전송");
            softly.assertThat(simulator.parcel(key, after(100)).orElseThrow().summary().manual()).isTrue();
        });
    }

    @Test
    void 없던_번호를_지정하면_그_사건_하나만_있는_송장이_된다() {
        // given
        CarrierSimulator simulator = newSimulator();
        ParcelKey key = new ParcelKey(CARRIER, "1234567890");

        // when
        simulator.sendManual(List.of(key), StatusCode.OUT_FOR_DELIVERY, 1, URL, START);
        List<TrackingEvent> sut = eventsOf(simulator.track(key, after(100)));

        // then
        assertThat(sut).extracting(TrackingEvent::status).containsExactly(StatusCode.OUT_FOR_DELIVERY);
    }

    @Test
    void 없는_송장_시나리오의_번호도_지정하면_조회된다() {
        // given
        CarrierSimulator simulator = newSimulator();
        ParcelKey key = new ParcelKey(CARRIER, "904000000001");

        // when
        simulator.sendManual(List.of(key), StatusCode.EXCEPTION, 1, URL, START);

        // then
        assertThat(eventsOf(simulator.track(key, START))).hasSize(1);
    }

    @Test
    void 지정한_횟수만큼_알림이_나가고_상태_변화_알림은_따로_나가지_않는다() {
        // given
        CarrierSimulator simulator = newSimulator();
        ParcelKey first = new ParcelKey(CARRIER, "901000000001");
        ParcelKey second = new ParcelKey(CARRIER, "901000000002");
        simulator.registerWebhook(first, "http://localhost:9999/registered", START.plus(Duration.ofHours(48)), START);
        simulator.pollDueCallbacks(START);

        // when
        simulator.sendManual(List.of(first, second), StatusCode.DELIVERED, 3, URL, after(1));
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(after(1));
        List<CallbackAttempt> later = simulator.pollDueCallbacks(after(40));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).hasSize(6);
            softly.assertThat(sut).extracting(CallbackAttempt::key)
                    .containsExactly(first, first, first, second, second, second);
            // 받는 곳을 주면 등록 주소도 그 주소로 바뀐다
            softly.assertThat(sut).extracting(CallbackAttempt::callbackUrl).containsOnly(URL);
            softly.assertThat(later).isEmpty();
        });
    }

    @Test
    void 받는_곳을_비우면_등록된_주소로_나간다() {
        // given
        CarrierSimulator simulator = newSimulator();
        ParcelKey key = new ParcelKey(CARRIER, "901000000001");
        simulator.registerWebhook(key, "http://localhost:9999/registered", START.plus(Duration.ofHours(48)), START);
        simulator.pollDueCallbacks(START);

        // when
        simulator.sendManual(List.of(key), StatusCode.DELIVERED, 1, " ", after(1));
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(after(1));

        // then
        assertThat(sut).extracting(CallbackAttempt::callbackUrl).containsExactly("http://localhost:9999/registered");
    }

    @Test
    void 받는_곳도_등록도_없으면_예외이고_아무_송장도_바꾸지_않는다() {
        // given: 첫 송장은 등록이 있고 둘째는 없다
        CarrierSimulator simulator = newSimulator();
        ParcelKey registered = new ParcelKey(CARRIER, "901000000001");
        ParcelKey unknown = new ParcelKey(CARRIER, "901000000002");
        simulator.registerWebhook(registered, URL, START.plus(Duration.ofHours(48)), START);
        simulator.pollDueCallbacks(START);

        // when & then
        assertThatThrownBy(() -> simulator.sendManual(List.of(registered, unknown), StatusCode.DELIVERED, 1, null, START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("받는 곳 주소를 넣지 않았는데 알림 등록이 없는 송장입니다: 901000000002");
        assertSoftly(softly -> {
            softly.assertThat(simulator.parcel(registered, START).orElseThrow().summary().manual()).isFalse();
            softly.assertThat(simulator.parcel(unknown, START)).isEmpty();
            softly.assertThat(simulator.stats(START).pendingCallbacks()).isZero();
        });
    }

    @ParameterizedTest
    @MethodSource("잘못된_횟수")
    void 횟수가_범위를_벗어나면_예외다(int times) {
        // given
        CarrierSimulator sut = newSimulator();
        List<ParcelKey> keys = List.of(new ParcelKey(CARRIER, "901000000001"));

        // when & then
        assertThatThrownBy(() -> sut.sendManual(keys, StatusCode.DELIVERED, times, URL, START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("보낼 횟수는 1 이상 100 이하여야 합니다.");
    }

    static Stream<Integer> 잘못된_횟수() {
        return Stream.of(0, -1, 101);
    }

    @ParameterizedTest
    @MethodSource("잘못된_송장_목록")
    void 송장_목록이_비거나_너무_많으면_예외다(List<ParcelKey> keys) {
        // given
        CarrierSimulator sut = newSimulator();

        // when & then
        assertThatThrownBy(() -> sut.sendManual(keys, StatusCode.DELIVERED, 1, URL, START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("송장 번호는 1개 이상 1,000개 이하로 넣어야 합니다.");
    }

    static Stream<List<ParcelKey>> 잘못된_송장_목록() {
        List<ParcelKey> tooMany = Stream.iterate(1, i -> i + 1).limit(1001)
                .map(i -> new ParcelKey(CARRIER, Scenario.NORMAL.trackingNumber(i)))
                .toList();
        return Stream.of(null, List.of(), tooMany);
    }

    @Test
    void 연달아_만든_더미도_상태가_바뀌는_순간이_퍼진다() {
        // given: 흔들림 ±10%. 집화는 기준 4초라 3.6초에서 4.4초 사이에 퍼져야 한다
        DispatchSettings dispatch = new DispatchSettings(Duration.ofSeconds(1), 100, 10, false);
        CarrierSimulator simulator = new CarrierSimulator(
                new SimulatorSettings(1, 24, 0.1, Duration.ofHours(1), dispatch), START);
        List<ParcelKey> keys = simulator.createDummies(Scenario.NORMAL, CARRIER, 100, null, START);

        // when
        List<Long> pickupMillis = keys.stream()
                .map(key -> eventsOf(simulator.track(key, after(10))).get(1).time())
                .map(time -> Duration.between(START, time).toMillis())
                .sorted()
                .toList();

        // then
        assertSoftly(softly -> {
            softly.assertThat(pickupMillis.getFirst()).isBetween(3600L, 3800L);
            softly.assertThat(pickupMillis.getLast()).isBetween(4200L, 4400L);
            softly.assertThat(pickupMillis.stream().distinct().count()).isGreaterThan(90);
        });
    }

    @Test
    void 더미_10만_건을_만들고_목록_첫_쪽을_읽을_수_있다() {
        // given
        CarrierSimulator simulator = newSimulator();

        // when
        long startedAt = System.nanoTime();
        simulator.createDummies(Scenario.NORMAL, CARRIER, 100_000, URL, START);
        ParcelPage sut = simulator.parcels(ParcelFilter.all(), 0, 50, after(10));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.total()).isEqualTo(100_000);
            softly.assertThat(sut.items()).hasSize(50);
            softly.assertThat(sut.items().getFirst().lastStatus()).isEqualTo(StatusCode.IN_TRANSIT);
            // 정확한 속도가 아니라 송장 수에 따라 급격히(제곱으로) 느려지지 않는지만 본다. 느린 컴퓨터에서도 흔들리지 않게 넉넉히 둔다
            softly.assertThat(elapsedMillis).isLessThan(20_000);
        });
    }
}
