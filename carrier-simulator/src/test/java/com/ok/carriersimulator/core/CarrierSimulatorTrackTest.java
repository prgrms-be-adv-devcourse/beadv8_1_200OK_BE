package com.ok.carriersimulator.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// 재생 속도는 가상 1시간 = 실제 1초(secondsPerDay 24), 흔들림은 0으로 두어 사건 시각을 초 단위로 맞춘다
class CarrierSimulatorTrackTest {

    private static final Instant START = Instant.parse("2026-11-14T00:00:00Z");
    private static final String CARRIER = "kr.cjlogistics";
    private static final ParcelKey NORMAL = new ParcelKey(CARRIER, "901000000001");

    private static CarrierSimulator newSimulator(double jitter) {
        DispatchSettings dispatch = new DispatchSettings(Duration.ofSeconds(1), 100, 10, false);
        return new CarrierSimulator(new SimulatorSettings(1, 24, jitter, Duration.ofHours(1), dispatch), START);
    }

    private static Instant after(long seconds) {
        return START.plusSeconds(seconds);
    }

    private static List<TrackingEvent> eventsOf(TrackResult result) {
        assertThat(result).isInstanceOf(TrackResult.Found.class);
        return ((TrackResult.Found) result).events();
    }

    @Test
    void 처음_조회한_송장은_접수_상태다() {
        // given
        CarrierSimulator simulator = newSimulator(0);

        // when
        List<TrackingEvent> sut = eventsOf(simulator.track(NORMAL, START));

        // then
        assertThat(sut).hasSize(1);
        assertSoftly(softly -> {
            softly.assertThat(sut.getFirst().status()).isEqualTo(StatusCode.INFORMATION_RECEIVED);
            softly.assertThat(sut.getFirst().time()).isEqualTo(START);
        });
    }

    @Test
    void 시간이_지나면_정상_시나리오대로_진행한다() {
        // given
        CarrierSimulator simulator = newSimulator(0);
        simulator.track(NORMAL, START);

        // when
        List<TrackingEvent> sut = eventsOf(simulator.track(NORMAL, after(30)));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).extracting(TrackingEvent::status).containsExactly(
                    StatusCode.INFORMATION_RECEIVED, StatusCode.AT_PICKUP, StatusCode.IN_TRANSIT,
                    StatusCode.IN_TRANSIT, StatusCode.OUT_FOR_DELIVERY, StatusCode.DELIVERED);
            softly.assertThat(sut).extracting(TrackingEvent::time).containsExactly(
                    after(0), after(4), after(10), after(20), after(26), after(30));
        });
    }

    @ParameterizedTest
    @MethodSource("시나리오와_마지막_상태")
    void 시나리오별_마지막_상태(String trackingNumber, StatusCode expected) {
        // given
        CarrierSimulator simulator = newSimulator(0);
        ParcelKey key = new ParcelKey(CARRIER, trackingNumber);
        simulator.track(key, START);

        // when
        List<TrackingEvent> sut = eventsOf(simulator.track(key, after(200)));

        // then
        assertThat(sut.getLast().status()).isEqualTo(expected);
    }

    static Stream<Arguments> 시나리오와_마지막_상태() {
        return Stream.of(
                Arguments.of("902000000001", StatusCode.DELIVERED),
                Arguments.of("903000000001", StatusCode.IN_TRANSIT),
                Arguments.of("906000000001", StatusCode.DELIVERED),
                Arguments.of("908000000001", StatusCode.EXCEPTION),
                // 규칙에 맞지 않는 번호는 정상 시나리오다
                Arguments.of("1234567890", StatusCode.DELIVERED));
    }

    @Test
    void 없는_송장은_언제나_찾을_수_없다() {
        // given
        CarrierSimulator sut = newSimulator(0);
        ParcelKey key = new ParcelKey(CARRIER, "904000000001");

        // when & then
        assertSoftly(softly -> {
            softly.assertThat(sut.track(key, START)).isInstanceOf(TrackResult.NotFound.class);
            softly.assertThat(sut.track(key, after(200))).isInstanceOf(TrackResult.NotFound.class);
        });
    }

    @Test
    void 늦게_등록되는_송장은_6시간_뒤부터_보인다() {
        // given
        CarrierSimulator sut = newSimulator(0);
        ParcelKey key = new ParcelKey(CARRIER, "905000000001");

        // when
        TrackResult atStart = sut.track(key, START);
        TrackResult beforeRegister = sut.track(key, after(5));
        TrackResult afterRegister = sut.track(key, after(6));

        // then
        assertSoftly(softly -> {
            softly.assertThat(atStart).isInstanceOf(TrackResult.NotFound.class);
            softly.assertThat(beforeRegister).isInstanceOf(TrackResult.NotFound.class);
            softly.assertThat(eventsOf(afterRegister)).extracting(TrackingEvent::status)
                    .containsExactly(StatusCode.INFORMATION_RECEIVED);
        });
    }

    @Test
    void 이력_끼어듦은_뒤늦게_더_이른_시각의_사건이_추가된다() {
        // given
        CarrierSimulator sut = newSimulator(0);
        ParcelKey key = new ParcelKey(CARRIER, "907000000001");
        sut.track(key, START);

        // when
        List<TrackingEvent> before = eventsOf(sut.track(key, after(21)));
        List<TrackingEvent> afterInsert = eventsOf(sut.track(key, after(22)));

        // then
        assertSoftly(softly -> {
            softly.assertThat(before).extracting(TrackingEvent::time).containsExactly(after(0), after(4), after(20));
            softly.assertThat(afterInsert).extracting(TrackingEvent::time)
                    .containsExactly(after(0), after(4), after(10), after(20));
            // 마지막 상태는 그대로다
            softly.assertThat(afterInsert.getLast()).isEqualTo(before.getLast());
        });
    }

    @Test
    void 같은_설정이면_같은_이력이_나온다() {
        // given
        CarrierSimulator first = newSimulator(0.1);
        CarrierSimulator second = newSimulator(0.1);
        first.track(NORMAL, START);
        second.track(NORMAL, START);

        // when
        List<TrackingEvent> sut = eventsOf(first.track(NORMAL, after(40)));
        List<TrackingEvent> other = eventsOf(second.track(NORMAL, after(40)));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).hasSize(6).isEqualTo(other);
            // 흔들림이 있으면 사건 시각이 기준(4초)에서 조금 벗어난다
            softly.assertThat(sut.get(1).time()).isNotEqualTo(after(4));
            softly.assertThat(sut.get(1).time()).isBetween(START.plusMillis(3600), START.plusMillis(4400));
        });
    }

    @Test
    void 속도를_바꾸거나_일시정지해도_지난_사건의_시각은_그대로다() {
        // given
        CarrierSimulator simulator = newSimulator(0);
        simulator.track(NORMAL, START);
        simulator.track(NORMAL, after(5));

        // when
        simulator.changeSpeed(12, after(5));
        simulator.pause(after(6));
        simulator.resume(after(8));
        List<TrackingEvent> sut = eventsOf(simulator.track(NORMAL, after(20)));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.get(0).time()).isEqualTo(after(0));
            softly.assertThat(sut.get(1).time()).isEqualTo(after(4));
            // 5초(가상 5시간)에 속도가 두 배가 되어 6초에 가상 7시간에서 멈춘다. 8초에 다시 재생해 1.5초 뒤 가상 10시간이 된다
            softly.assertThat(sut.get(2).time()).isEqualTo(START.plusMillis(9500));
            softly.assertThat(sut.getLast().status()).isEqualTo(StatusCode.DELIVERED);
        });
    }

    @Test
    void 택배사가_다르면_같은_번호도_다른_송장이다() {
        // given
        CarrierSimulator sut = newSimulator(0);
        ParcelKey other = new ParcelKey("kr.epost", NORMAL.trackingNumber());
        sut.track(NORMAL, START);

        // when
        List<TrackingEvent> otherEvents = eventsOf(sut.track(other, after(10)));
        List<TrackingEvent> events = eventsOf(sut.track(NORMAL, after(10)));

        // then
        assertSoftly(softly -> {
            softly.assertThat(otherEvents).hasSize(1);
            softly.assertThat(events).hasSize(3);
            softly.assertThat(sut.parcels(ParcelFilter.all(), 0, 50, after(10)).total()).isEqualTo(2);
        });
    }

    @Test
    void 동시에_조회해도_송장은_하나만_생긴다() throws InterruptedException {
        // given
        CarrierSimulator sut = newSimulator(0);
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        // when
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ready.countDown();
                go.await();
                sut.track(NORMAL, START);
                sut.registerWebhook(NORMAL, "http://localhost:1/callback", after(3600), START);
                return null;
            });
        }
        ready.await();
        go.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);

        // then
        ParcelPage page = sut.parcels(ParcelFilter.all(), 0, 50, START);
        assertSoftly(softly -> {
            softly.assertThat(finished).isTrue();
            softly.assertThat(page.total()).isEqualTo(1);
            softly.assertThat(page.items().getFirst().queryCount()).isEqualTo(threads);
        });
    }

    @Test
    void 등록하면_송장이_생기고_등록이_남는다() {
        // given
        CarrierSimulator simulator = newSimulator(0);

        // when
        simulator.registerWebhook(NORMAL, "http://localhost:8080/callback", after(172_800), START);
        ParcelDetail sut = simulator.parcel(NORMAL, START).orElseThrow();

        // then
        assertThat(sut.registration()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.registration().callbackUrl()).isEqualTo("http://localhost:8080/callback");
            softly.assertThat(sut.registration().expirationTime()).isEqualTo(after(172_800));
            softly.assertThat(sut.events()).hasSize(1);
            softly.assertThat(simulator.stats(START).total(Metric.REGISTER)).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @MethodSource("지금이거나_지난_만료_시각")
    void 만료_시각이_지난_등록은_등록을_지운다(Instant expirationTime) {
        // given
        CarrierSimulator simulator = newSimulator(0);
        simulator.registerWebhook(NORMAL, "http://localhost:8080/callback", after(172_800), START);

        // when
        simulator.registerWebhook(NORMAL, "http://localhost:8080/callback", expirationTime, after(10));
        ParcelDetail sut = simulator.parcel(NORMAL, after(10)).orElseThrow();

        // then
        assertThat(sut.registration()).isNull();
    }

    static Stream<Instant> 지금이거나_지난_만료_시각() {
        return Stream.of(after(10), after(9), START.minusSeconds(86_400));
    }

    @Test
    void 없던_송장에_지난_만료_시각으로_등록해도_송장이_생기지_않는다() {
        // given
        CarrierSimulator sut = newSimulator(0);

        // when
        sut.registerWebhook(NORMAL, "http://localhost:8080/callback", START.minusSeconds(1), START);

        // then
        assertThat(sut.parcel(NORMAL, START)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("빈_주소")
    void 알림_받을_주소가_비면_예외다(String callbackUrl) {
        // given
        CarrierSimulator sut = newSimulator(0);

        // when & then
        assertThatThrownBy(() -> sut.registerWebhook(NORMAL, callbackUrl, after(3600), START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("알림 받을 주소는 필수입니다.");
    }

    static Stream<String> 빈_주소() {
        return Stream.of(null, "", " ");
    }

    @Test
    void 처음_응답에_실린_시각과_감지_지연을_남긴다() {
        // given: 집화는 4초에 일어나고, 7초에 처음 조회해 간다
        CarrierSimulator simulator = newSimulator(0);
        simulator.track(NORMAL, START);

        // when
        simulator.track(NORMAL, after(7));
        simulator.track(NORMAL, after(9));
        ParcelDetail sut = simulator.parcel(NORMAL, after(9)).orElseThrow();
        StatsSnapshot stats = simulator.stats(after(9));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.events().get(0).firstFetchedAt()).isEqualTo(START);
            softly.assertThat(sut.events().get(1).event().time()).isEqualTo(after(4));
            softly.assertThat(sut.events().get(1).firstFetchedAt()).isEqualTo(after(7));
            softly.assertThat(sut.summary().queryCount()).isEqualTo(3);
            softly.assertThat(sut.summary().lastQueriedAt()).isEqualTo(after(9));
            // 표본은 접수(0초)와 집화(3초) 둘이다
            softly.assertThat(stats.detectionP50()).isEqualTo(Duration.ZERO);
            softly.assertThat(stats.detectionP95()).isEqualTo(Duration.ofSeconds(3));
        });
    }

    @Test
    void 처음_조회된_시각은_첫_조회의_시각이고_조회한_적이_없으면_비어_있다() {
        // given
        CarrierSimulator simulator = newSimulator(0);
        ParcelKey notQueried = simulator.createDummies(Scenario.SLOW, CARRIER, 1, null, START).getFirst();
        simulator.track(NORMAL, after(3));

        // when
        simulator.track(NORMAL, after(9));
        ParcelView queried = simulator.parcel(NORMAL, after(9)).orElseThrow().summary();
        ParcelView sut = simulator.parcel(notQueried, after(9)).orElseThrow().summary();

        // then
        assertSoftly(softly -> {
            softly.assertThat(queried.firstQueriedAt()).isEqualTo(after(3));
            softly.assertThat(queried.lastQueriedAt()).isEqualTo(after(9));
            softly.assertThat(sut.firstQueriedAt()).isNull();
        });
    }

    @Test
    void 조회한_적이_없으면_감지_지연이_없다() {
        // given
        CarrierSimulator simulator = newSimulator(0);

        // when
        StatsSnapshot sut = simulator.stats(START);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.detectionP50()).isNull();
            softly.assertThat(sut.detectionP95()).isNull();
            softly.assertThat(sut.parcelCount()).isZero();
        });
    }

    @Test
    void 오류_비율이_100이면_언제나_거절한다() {
        // given
        CarrierSimulator sut = newSimulator(0);
        String token = sut.issueToken(START);
        sut.updateFaults(new FaultSettings(250, 100, 1, false));

        // when & then
        for (int i = 0; i < 20; i++) {
            Admission admission = sut.admit(token, Operation.TRACK, START);
            assertThat(admission).isEqualTo(new Admission(250, Admission.Rejection.INTERNAL));
        }
        assertThat(sut.stats(START).total(Metric.TRACK_ERROR)).isEqualTo(20);
    }

    @Test
    void 오류_비율이_0이면_거절하지_않는다() {
        // given
        CarrierSimulator sut = newSimulator(0);
        String token = sut.issueToken(START);

        // when & then
        for (int i = 0; i < 20; i++) {
            assertThat(sut.admit(token, Operation.TRACK, START).rejected()).isFalse();
        }
    }

    @Test
    void 등록_요청에_일부러_낸_오류는_조회_오류가_아니라_등록_오류로_센다() {
        // given
        CarrierSimulator sut = newSimulator(0);
        String token = sut.issueToken(START);
        sut.updateFaults(new FaultSettings(0, 100, 1, false));

        // when
        for (int i = 0; i < 5; i++) {
            sut.admit(token, Operation.REGISTER, START);
        }
        sut.admit(token, Operation.TRACK, START);

        // then
        StatsSnapshot stats = sut.stats(START);
        assertSoftly(softly -> {
            softly.assertThat(stats.total(Metric.REGISTER_ERROR)).isEqualTo(5);
            softly.assertThat(stats.total(Metric.TRACK_ERROR)).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @MethodSource("쓸_수_없는_토큰")
    void 쓸_수_없는_토큰은_거절한다(String token) {
        // given
        CarrierSimulator sut = newSimulator(0);

        // when
        Admission admission = sut.admit(token, Operation.TRACK, START);

        // then
        assertSoftly(softly -> {
            softly.assertThat(admission.rejection()).isEqualTo(Admission.Rejection.UNAUTHENTICATED);
            softly.assertThat(sut.stats(START).total(Metric.UNAUTHENTICATED)).isEqualTo(1);
        });
    }

    static Stream<String> 쓸_수_없는_토큰() {
        return Stream.of(null, "", "모르는-토큰");
    }

    @Test
    void 만료시킨_토큰은_거절한다() {
        // given
        CarrierSimulator sut = newSimulator(0);
        String token = sut.issueToken(START);
        boolean admittedBefore = !sut.admit(token, Operation.TRACK, START).rejected();

        // when
        sut.expireTokens();

        // then
        assertSoftly(softly -> {
            softly.assertThat(admittedBefore).isTrue();
            softly.assertThat(sut.admit(token, Operation.TRACK, START).rejection()).isEqualTo(Admission.Rejection.UNAUTHENTICATED);
        });
    }

    @Test
    void 송장을_번호_앞자리와_시나리오와_상태로_거른다() {
        // given
        CarrierSimulator sut = newSimulator(0);
        sut.track(new ParcelKey(CARRIER, "901000000001"), START);
        sut.track(new ParcelKey(CARRIER, "901000000002"), START);
        sut.track(new ParcelKey(CARRIER, "903000000001"), START);
        sut.track(new ParcelKey(CARRIER, "904000000001"), START);

        // when
        ParcelPage byPrefix = sut.parcels(new ParcelFilter("9010", null, null), 0, 50, after(100));
        ParcelPage byScenario = sut.parcels(new ParcelFilter(null, Scenario.STUCK, null), 0, 50, after(100));
        ParcelPage byStatus = sut.parcels(new ParcelFilter(null, null, StatusCode.DELIVERED), 0, 50, after(100));
        ParcelPage byAll = sut.parcels(new ParcelFilter("90", Scenario.NORMAL, StatusCode.DELIVERED), 1, 1, after(100));

        // then
        assertSoftly(softly -> {
            softly.assertThat(byPrefix.total()).isEqualTo(2);
            softly.assertThat(byScenario.items()).extracting(view -> view.key().trackingNumber())
                    .containsExactly("903000000001");
            softly.assertThat(byStatus.total()).isEqualTo(2);
            softly.assertThat(byAll.total()).isEqualTo(2);
            softly.assertThat(byAll.items()).extracting(view -> view.key().trackingNumber())
                    .containsExactly("901000000002");
        });
    }

    @Test
    void 쪽_크기만큼만_만든_순서로_돌려준다() {
        // given
        CarrierSimulator sut = newSimulator(0);
        for (int i = 1; i <= 5; i++) {
            sut.track(new ParcelKey(CARRIER, Scenario.NORMAL.trackingNumber(i)), START);
        }

        // when
        ParcelPage second = sut.parcels(ParcelFilter.all(), 1, 2, START);
        ParcelPage beyond = sut.parcels(ParcelFilter.all(), 9, 2, START);

        // then
        assertSoftly(softly -> {
            softly.assertThat(second.total()).isEqualTo(5);
            softly.assertThat(second.items()).extracting(view -> view.key().trackingNumber())
                    .containsExactly("901000000003", "901000000004");
            softly.assertThat(beyond.items()).isEmpty();
        });
    }

    @ParameterizedTest
    @MethodSource("잘못된_쪽_요청")
    void 쪽_번호나_크기가_범위를_벗어나면_예외다(int page, int size) {
        // given
        CarrierSimulator sut = newSimulator(0);

        // when & then
        assertThatThrownBy(() -> sut.parcels(ParcelFilter.all(), page, size, START))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> 잘못된_쪽_요청() {
        return Stream.of(Arguments.of(-1, 50), Arguments.of(0, 0), Arguments.of(0, 201));
    }

    @Test
    void 상태별_송장_수와_조회_수를_센다() {
        // given
        CarrierSimulator simulator = newSimulator(0);
        simulator.track(new ParcelKey(CARRIER, "901000000001"), START);
        simulator.track(new ParcelKey(CARRIER, "903000000001"), START);
        simulator.track(new ParcelKey(CARRIER, "904000000001"), START);

        // when
        StatsSnapshot sut = simulator.stats(after(100));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.parcelCount()).isEqualTo(3);
            softly.assertThat(sut.statusCounts()).containsEntry("배송 완료", 1L)
                    .containsEntry("이동 중", 1L)
                    .containsEntry("없는 송장", 1L)
                    .containsEntry("집화", 0L);
            softly.assertThat(sut.total(Metric.TRACK_OK)).isEqualTo(2);
            softly.assertThat(sut.total(Metric.TRACK_NOT_FOUND)).isEqualTo(1);
            softly.assertThat(sut.buckets()).hasSize(360);
        });
    }

    @Test
    void 통계만_지우면_송장은_남고_모두_지우면_송장도_사라진다() {
        // given
        CarrierSimulator sut = newSimulator(0);
        sut.track(NORMAL, START);

        // when
        sut.clearStats();
        StatsSnapshot afterClearStats = sut.stats(START);
        sut.clearAll();
        StatsSnapshot afterClearAll = sut.stats(START);

        // then
        assertSoftly(softly -> {
            softly.assertThat(afterClearStats.total(Metric.TRACK_OK)).isZero();
            softly.assertThat(afterClearStats.detectionP50()).isNull();
            softly.assertThat(afterClearStats.parcelCount()).isEqualTo(1);
            softly.assertThat(afterClearAll.parcelCount()).isZero();
            softly.assertThat(sut.parcel(NORMAL, START)).isEmpty();
        });
    }

    @ParameterizedTest
    @MethodSource("빈_키_값")
    void 택배사나_송장_번호가_비면_예외다(String carrierId, String trackingNumber) {
        // when & then
        assertThatThrownBy(() -> new ParcelKey(carrierId, trackingNumber))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> 빈_키_값() {
        return Stream.of(
                Arguments.of(null, "901000000001"),
                Arguments.of(" ", "901000000001"),
                Arguments.of(CARRIER, null),
                Arguments.of(CARRIER, ""));
    }
}
