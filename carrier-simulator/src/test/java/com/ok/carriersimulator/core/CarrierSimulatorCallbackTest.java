package com.ok.carriersimulator.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// 재생 속도는 가상 1시간 = 실제 1초, 흔들림은 0이다. 정상 송장은 4, 10, 20, 26, 30초에 상태가 바뀐다
class CarrierSimulatorCallbackTest {

    private static final Instant START = Instant.parse("2026-11-14T00:00:00Z");
    private static final String CARRIER = "kr.cjlogistics";
    private static final String URL = "http://localhost:8080/carrier-callback";
    private static final String OTHER_URL = "http://localhost:9090/other-callback";
    private static final ParcelKey NORMAL = new ParcelKey(CARRIER, "901000000001");
    // 사건이 하나도 없는 송장. 등록 알림 말고는 알림이 생기지 않아 재전송만 따로 볼 수 있다
    private static final ParcelKey SILENT = new ParcelKey(CARRIER, "904000000001");
    private static final Instant FAR = START.plus(Duration.ofHours(48));

    private static CarrierSimulator newSimulator() {
        DispatchSettings dispatch = new DispatchSettings(Duration.ofSeconds(1), 100, 10, false);
        return new CarrierSimulator(new SimulatorSettings(1, 24, 0, Duration.ofHours(1), dispatch), START);
    }

    private static Instant after(long seconds) {
        return START.plusSeconds(seconds);
    }

    private static CallbackOutcome failure() {
        return CallbackOutcome.responded(500, 12);
    }

    @Test
    void 처음_등록하면_알림이_한_건_나간다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(NORMAL, URL, FAR, START);

        // when
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(START);

        // then
        assertThat(sut).hasSize(1);
        assertSoftly(softly -> {
            softly.assertThat(sut.getFirst().key()).isEqualTo(NORMAL);
            softly.assertThat(sut.getFirst().callbackUrl()).isEqualTo(URL);
            softly.assertThat(sut.getFirst().attemptNo()).isEqualTo(1);
        });
    }

    @Test
    void 다시_등록해도_알림은_추가되지_않고_만료_시각만_바뀐다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(NORMAL, URL, FAR, START);

        // when
        simulator.registerWebhook(NORMAL, URL, FAR.plusSeconds(3600), after(1));
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(after(1));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).hasSize(1);
            softly.assertThat(simulator.parcel(NORMAL, after(1)).orElseThrow().registration().expirationTime())
                    .isEqualTo(FAR.plusSeconds(3600));
        });
    }

    @Test
    void 만료된_뒤에_다시_등록하면_알림이_다시_나간다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(SILENT, URL, after(2), START);
        simulator.pollDueCallbacks(START);

        // when
        simulator.registerWebhook(SILENT, URL, FAR, after(3));
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(after(3));

        // then
        assertThat(sut).hasSize(1);
    }

    @Test
    void 상태가_바뀔_때마다_알림이_나간다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(NORMAL, URL, FAR, START);

        // when
        List<Long> sentAtSeconds = new ArrayList<>();
        for (long second = 0; second <= 35; second++) {
            for (CallbackAttempt ignored : simulator.pollDueCallbacks(after(second))) {
                sentAtSeconds.add(second);
            }
        }

        // then: 등록 알림 1건과 상태 변화 5건
        assertThat(sentAtSeconds).containsExactly(0L, 4L, 10L, 20L, 26L, 30L);
    }

    @Test
    void 조회가_먼저_사건을_가져가도_알림은_나간다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(NORMAL, URL, FAR, START);
        simulator.pollDueCallbacks(START);

        // when: 알림을 꺼내기 전에 조회가 집화 사건을 먼저 본다
        simulator.track(NORMAL, after(5));
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(after(5));

        // then
        assertThat(sut).hasSize(1);
    }

    @Test
    void 이력이_끼어들면_마지막_상태가_같아도_알림이_나간다() {
        // given
        CarrierSimulator simulator = newSimulator();
        ParcelKey key = new ParcelKey(CARRIER, "907000000001");
        simulator.registerWebhook(key, URL, FAR, START);
        for (long second = 0; second <= 21; second++) {
            simulator.pollDueCallbacks(after(second));
        }
        StatusCode before = simulator.parcel(key, after(21)).orElseThrow().summary().lastStatus();

        // when
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(after(22));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).hasSize(1);
            softly.assertThat(simulator.parcel(key, after(22)).orElseThrow().summary().lastStatus()).isEqualTo(before);
        });
    }

    @Test
    void 하루를_건너뛰면_그사이_변화의_알림이_한꺼번에_쌓인다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(NORMAL, URL, FAR, START);
        simulator.pollDueCallbacks(START);

        // when: 가상 24시간 안에는 집화, 이동 중 두 번이 있다
        simulator.skipDay(after(1));
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(after(1));

        // then
        assertThat(sut).hasSize(3);
    }

    @Test
    void 간격이_지나기_전에는_꺼내지_않는다() {
        // given
        CarrierSimulator simulator = newSimulator();
        registerSilent(simulator, 150);

        // when
        int first = simulator.pollDueCallbacks(START).size();
        int tooEarly = simulator.pollDueCallbacks(START.plusMillis(900)).size();
        int second = simulator.pollDueCallbacks(after(1)).size();

        // then
        assertSoftly(softly -> {
            softly.assertThat(first).isEqualTo(100);
            softly.assertThat(tooEarly).isZero();
            softly.assertThat(second).isEqualTo(50);
        });
    }

    @Test
    void 한_번에_정한_개수까지만_넣은_순서로_꺼낸다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.updateDispatch(new DispatchSettings(Duration.ofSeconds(1), 3, 10, false));
        registerSilent(simulator, 5);

        // when
        List<CallbackAttempt> first = simulator.pollDueCallbacks(START);
        List<CallbackAttempt> second = simulator.pollDueCallbacks(after(1));

        // then
        assertSoftly(softly -> {
            softly.assertThat(first).extracting(attempt -> attempt.key().trackingNumber())
                    .containsExactly("904000000001", "904000000002", "904000000003");
            softly.assertThat(second).extracting(attempt -> attempt.key().trackingNumber())
                    .containsExactly("904000000004", "904000000005");
            softly.assertThat(simulator.stats(after(1)).pendingCallbacks()).isZero();
        });
    }

    @Test
    void 멈춰_두면_꺼내지_않고_다시_켜면_이어서_꺼낸다() {
        // given
        CarrierSimulator simulator = newSimulator();
        registerSilent(simulator, 2);
        simulator.updateDispatch(simulator.dispatch().withPaused(true));

        // when
        int whilePaused = simulator.pollDueCallbacks(START).size();
        long pending = simulator.stats(START).pendingCallbacks();
        simulator.updateDispatch(simulator.dispatch().withPaused(false));
        int afterResume = simulator.pollDueCallbacks(after(1)).size();

        // then
        assertSoftly(softly -> {
            softly.assertThat(whilePaused).isZero();
            softly.assertThat(pending).isEqualTo(2);
            softly.assertThat(afterResume).isEqualTo(2);
        });
    }

    @Test
    void 실패하면_1초_2초_4초_뒤에_다시_나간다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(SILENT, URL, FAR, START);
        CallbackAttempt first = simulator.pollDueCallbacks(START).getFirst();

        // when
        simulator.completeCallback(first, failure(), START);
        int beforeOneSecond = simulator.pollDueCallbacks(START.plusMillis(999)).size();
        CallbackAttempt second = simulator.pollDueCallbacks(after(1)).getFirst();
        simulator.completeCallback(second, failure(), after(1));
        int beforeThreeSeconds = simulator.pollDueCallbacks(START.plusMillis(2999)).size();
        CallbackAttempt third = simulator.pollDueCallbacks(after(3)).getFirst();
        simulator.completeCallback(third, failure(), after(3));
        int beforeSevenSeconds = simulator.pollDueCallbacks(START.plusMillis(6999)).size();
        CallbackAttempt fourth = simulator.pollDueCallbacks(after(7)).getFirst();

        // then
        assertSoftly(softly -> {
            softly.assertThat(beforeOneSecond).isZero();
            softly.assertThat(beforeThreeSeconds).isZero();
            softly.assertThat(beforeSevenSeconds).isZero();
            softly.assertThat(List.of(second.attemptNo(), third.attemptNo(), fourth.attemptNo())).containsExactly(2, 3, 4);
            softly.assertThat(fourth.key()).isEqualTo(SILENT);
            softly.assertThat(fourth.callbackUrl()).isEqualTo(URL);
        });
    }

    @Test
    void 알림을_줄에_넣은_뒤_주소를_바꿔_다시_등록하면_바뀐_주소로_나간다() {
        // given: 보내기를 멈춰 둔 동안 등록 알림이 줄에 있다
        CarrierSimulator simulator = newSimulator();
        simulator.updateDispatch(new DispatchSettings(Duration.ofSeconds(1), 100, 10, true));
        simulator.registerWebhook(NORMAL, URL, FAR, START);

        // when
        simulator.registerWebhook(NORMAL, OTHER_URL, FAR, after(1));
        simulator.updateDispatch(new DispatchSettings(Duration.ofSeconds(1), 100, 10, false));
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(after(1));

        // then
        assertThat(sut).extracting(CallbackAttempt::callbackUrl).containsExactly(OTHER_URL);
    }

    @Test
    void 실패한_알림의_재전송은_그_사이_바뀐_주소로_나간다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(SILENT, URL, FAR, START);
        CallbackAttempt first = simulator.pollDueCallbacks(START).getFirst();
        simulator.completeCallback(first, failure(), START);

        // when
        simulator.registerWebhook(SILENT, OTHER_URL, FAR, START.plusMillis(500));
        CallbackAttempt sut = simulator.pollDueCallbacks(after(1)).getFirst();

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.callbackUrl()).isEqualTo(OTHER_URL);
            softly.assertThat(sut.attemptNo()).isEqualTo(2);
        });
    }

    @ParameterizedTest
    @MethodSource("시도_번호와_재전송_간격")
    void 재전송_간격은_두_배씩_늘고_5분을_넘지_않는다(int attemptNo, long delaySeconds) {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(SILENT, URL, FAR, START);
        simulator.pollDueCallbacks(START);

        // when
        simulator.completeCallback(new CallbackAttempt(999, SILENT, URL, attemptNo), failure(), after(10));
        int justBefore = simulator.pollDueCallbacks(after(10 + delaySeconds).minusMillis(1)).size();
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(after(10 + delaySeconds));

        // then
        assertSoftly(softly -> {
            softly.assertThat(justBefore).isZero();
            softly.assertThat(sut).hasSize(1);
            softly.assertThat(sut.getFirst().attemptNo()).isEqualTo(attemptNo + 1);
        });
    }

    static Stream<Arguments> 시도_번호와_재전송_간격() {
        return Stream.of(
                Arguments.of(1, 1), Arguments.of(5, 16), Arguments.of(9, 256),
                Arguments.of(10, 300), Arguments.of(40, 300), Arguments.of(1000, 300));
    }

    @Test
    void 성공하면_다시_보내지_않는다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(SILENT, URL, FAR, START);
        CallbackAttempt attempt = simulator.pollDueCallbacks(START).getFirst();

        // when
        simulator.completeCallback(attempt, CallbackOutcome.responded(202, 8), START);

        // then
        StatsSnapshot sut = simulator.stats(after(600));
        assertSoftly(softly -> {
            softly.assertThat(simulator.pollDueCallbacks(after(600))).isEmpty();
            softly.assertThat(sut.pendingCallbacks()).isZero();
            softly.assertThat(sut.total(Metric.CALLBACK_OK)).isEqualTo(1);
            softly.assertThat(sut.total(Metric.CALLBACK_FAIL)).isZero();
        });
    }

    @Test
    void 만료_시각_뒤에는_다시_보내지_않는다() {
        // given: 2초 뒤에 만료되는 등록
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(SILENT, URL, after(2), START);
        CallbackAttempt first = simulator.pollDueCallbacks(START).getFirst();
        simulator.completeCallback(first, failure(), START);
        CallbackAttempt second = simulator.pollDueCallbacks(after(1)).getFirst();

        // when: 다음 재전송은 3초라 만료 뒤다
        simulator.completeCallback(second, failure(), after(1));

        // then
        assertSoftly(softly -> {
            softly.assertThat(simulator.stats(after(1)).pendingCallbacks()).isZero();
            softly.assertThat(simulator.pollDueCallbacks(after(10))).isEmpty();
        });
    }

    @Test
    void 만료된_등록의_알림은_버린다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(SILENT, URL, after(5), START);

        // when
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(after(5));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).isEmpty();
            softly.assertThat(simulator.stats(after(5)).pendingCallbacks()).isZero();
        });
    }

    @Test
    void 등록이_만료되면_상태가_바뀌어도_알림을_넣지_않는다() {
        // given: 집화(4초) 전에 만료된다
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(NORMAL, URL, after(3), START);
        simulator.pollDueCallbacks(START);

        // when
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(after(30));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).isEmpty();
            softly.assertThat(simulator.stats(after(30)).pendingCallbacks()).isZero();
        });
    }

    @Test
    void 중복_설정만큼_같은_알림이_나간다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.updateFaults(new FaultSettings(0, 0, 3, false));
        simulator.registerWebhook(NORMAL, URL, FAR, START);
        int onRegister = simulator.pollDueCallbacks(START).size();

        // when
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(after(4));

        // then: 등록 알림은 한 건이고, 상태 변화 알림만 세 건이 된다
        assertSoftly(softly -> {
            softly.assertThat(onRegister).isEqualTo(1);
            softly.assertThat(sut).hasSize(3);
            softly.assertThat(sut).extracting(CallbackAttempt::key).containsOnly(NORMAL);
            softly.assertThat(sut).extracting(CallbackAttempt::id).doesNotHaveDuplicates();
        });
    }

    @Test
    void 섞어도_같은_시드면_같은_순서다() {
        // given
        CarrierSimulator first = newSimulator();
        CarrierSimulator second = newSimulator();
        for (CarrierSimulator simulator : List.of(first, second)) {
            simulator.updateFaults(new FaultSettings(0, 0, 1, true));
            registerSilent(simulator, 50);
        }

        // when
        List<String> sut = first.pollDueCallbacks(START).stream().map(a -> a.key().trackingNumber()).toList();
        List<String> other = second.pollDueCallbacks(START).stream().map(a -> a.key().trackingNumber()).toList();

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).hasSize(50).isEqualTo(other);
            softly.assertThat(sut).isNotEqualTo(sut.stream().sorted().toList());
        });
    }

    @Test
    void 보낸_기록에_응답과_걸린_시간이_남는다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(NORMAL, URL, FAR, START);
        CallbackAttempt attempt = simulator.pollDueCallbacks(START).getFirst();

        // when
        simulator.completeCallback(attempt, CallbackOutcome.responded(202, 35), START.plusMillis(35));
        simulator.completeCallback(new CallbackAttempt(77, NORMAL, URL, 2),
                CallbackOutcome.failed("시간 초과", 1000), after(3));
        List<CallbackRecord> sut = simulator.callbackRecords(null, 10);

        // then: 최근 것이 먼저다
        assertThat(sut).hasSize(2);
        assertSoftly(softly -> {
            softly.assertThat(sut.get(0).failure()).isEqualTo("시간 초과");
            softly.assertThat(sut.get(0).responseCode()).isNull();
            softly.assertThat(sut.get(0).success()).isFalse();
            softly.assertThat(sut.get(0).attemptNo()).isEqualTo(2);
            softly.assertThat(sut.get(0).sentAt()).isEqualTo(after(2));
            softly.assertThat(sut.get(1).responseCode()).isEqualTo(202);
            softly.assertThat(sut.get(1).success()).isTrue();
            softly.assertThat(sut.get(1).elapsedMillis()).isEqualTo(35);
            softly.assertThat(sut.get(1).sentAt()).isEqualTo(START);
            softly.assertThat(sut.get(1).lastStatus()).isEqualTo(StatusCode.INFORMATION_RECEIVED);
            softly.assertThat(sut.get(1).key()).isEqualTo(NORMAL);
        });
    }

    @Test
    void 송장_번호_앞자리로_기록을_찾는다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(NORMAL, URL, FAR, START);
        simulator.registerWebhook(SILENT, URL, FAR, START);
        for (CallbackAttempt attempt : simulator.pollDueCallbacks(START)) {
            simulator.completeCallback(attempt, CallbackOutcome.responded(200, 5), START);
        }

        // when
        List<CallbackRecord> sut = simulator.callbackRecords(" 9040 ", 10);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).extracting(CallbackRecord::key).containsExactly(SILENT);
            softly.assertThat(simulator.callbackRecords("", 1)).hasSize(1);
            softly.assertThat(simulator.callbackRecords("1", 10)).isEmpty();
        });
    }

    @ParameterizedTest
    @MethodSource("잘못된_기록_개수")
    void 기록_개수가_범위를_벗어나면_예외다(int limit) {
        // given
        CarrierSimulator sut = newSimulator();

        // when & then
        assertThatThrownBy(() -> sut.callbackRecords(null, limit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("기록 개수는 1 이상 1,000 이하여야 합니다.");
    }

    static Stream<Integer> 잘못된_기록_개수() {
        return Stream.of(0, -1, 1001);
    }

    @Test
    void 모두_지우면_대기_줄도_비고_지운_송장의_알림은_나가지_않는다() {
        // given
        CarrierSimulator simulator = newSimulator();
        simulator.registerWebhook(NORMAL, URL, FAR, START);

        // when
        simulator.clearAll();

        // then
        assertSoftly(softly -> {
            softly.assertThat(simulator.stats(START).pendingCallbacks()).isZero();
            softly.assertThat(simulator.pollDueCallbacks(after(30))).isEmpty();
        });
    }

    @ParameterizedTest
    @MethodSource("잘못된_전송_설정")
    void 전송_설정이_범위를_벗어나면_예외다(Duration interval, int batchSize, int concurrency, String message) {
        // when & then
        assertThatThrownBy(() -> new DispatchSettings(interval, batchSize, concurrency, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    static Stream<Arguments> 잘못된_전송_설정() {
        String interval = "보내는 간격은 0보다 크고 1시간 이하여야 합니다.";
        String batchSize = "한 번에 보내는 개수는 1 이상 10,000 이하여야 합니다.";
        String concurrency = "동시에 보내는 수는 1 이상 200 이하여야 합니다.";
        return Stream.of(
                Arguments.of(Duration.ZERO, 100, 10, interval),
                Arguments.of(Duration.ofSeconds(-1), 100, 10, interval),
                Arguments.of(Duration.ofSeconds(3601), 100, 10, interval),
                Arguments.of(null, 100, 10, interval),
                Arguments.of(Duration.ofSeconds(1), 0, 10, batchSize),
                Arguments.of(Duration.ofSeconds(1), 10_001, 10, batchSize),
                Arguments.of(Duration.ofSeconds(1), 100, 0, concurrency),
                Arguments.of(Duration.ofSeconds(1), 100, 201, concurrency));
    }

    @Test
    void 송장_10만_건에서도_꺼낼_수_있다() {
        // given: 등록한 정상 송장 10만 건. 4초에 모두 집화로 바뀐다
        CarrierSimulator simulator = newSimulator();
        for (int i = 1; i <= 100_000; i++) {
            simulator.registerWebhook(new ParcelKey(CARRIER, Scenario.NORMAL.trackingNumber(i)), URL, FAR, START);
        }
        simulator.updateDispatch(new DispatchSettings(Duration.ofMillis(100), 100, 10, false));

        // when: 변화가 한꺼번에 몰리는 순간과, 변화가 없는 평소 순간
        long burstStartedAt = System.nanoTime();
        List<CallbackAttempt> sut = simulator.pollDueCallbacks(after(4));
        long burstMillis = Duration.ofNanos(System.nanoTime() - burstStartedAt).toMillis();
        long idleStartedAt = System.nanoTime();
        simulator.pollDueCallbacks(after(5));
        long idleMillis = Duration.ofNanos(System.nanoTime() - idleStartedAt).toMillis();

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).hasSize(100);
            softly.assertThat(simulator.stats(after(5)).pendingCallbacks()).isEqualTo(199_800);
            // 정확한 속도가 아니라 송장 수에 따라 급격히(제곱으로) 느려지지 않는지만 본다. 느린 컴퓨터에서도 흔들리지 않게 넉넉히 둔다
            softly.assertThat(burstMillis).isLessThan(20_000);
            softly.assertThat(idleMillis).isLessThan(5_000);
        });
    }

    private static void registerSilent(CarrierSimulator simulator, int count) {
        for (int i = 1; i <= count; i++) {
            simulator.registerWebhook(new ParcelKey(CARRIER, Scenario.NOT_FOUND.trackingNumber(i)), URL, FAR, START);
        }
    }
}
