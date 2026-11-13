package com.ok.carriersimulator.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.jayway.jsonpath.JsonPath;
import com.ok.carriersimulator.core.CallbackAttempt;
import com.ok.carriersimulator.core.CallbackOutcome;
import com.ok.carriersimulator.core.CallbackRecord;
import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.DispatchSettings;
import com.ok.carriersimulator.core.FaultSettings;
import com.ok.carriersimulator.core.Metric;
import com.ok.carriersimulator.core.ParcelKey;
import com.ok.carriersimulator.core.Scenario;
import com.ok.carriersimulator.testsupport.FakeReceiver;
import com.ok.carriersimulator.testsupport.MutableClock;
import com.ok.carriersimulator.testsupport.SimulatorWebTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

// 타이머는 꺼 두고 tick()을 직접 부른다. 받는 쪽은 테스트 안에서 띄운 가짜 HTTP 서버다
@SimulatorWebTest
class CallbackDispatcherTest {

    private static final String CARRIER = "kr.cjlogistics";
    // 사건이 없는 송장이라 등록 알림 한 건만 나간다
    private static final ParcelKey SILENT = new ParcelKey(CARRIER, "904000000001");
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    private CallbackDispatcher sut;
    @Autowired
    private CallbackSender sender;
    @Autowired
    private CarrierSimulator simulator;
    @Autowired
    private MutableClock clock;

    private FakeReceiver receiver;

    @BeforeEach
    void setUp() {
        simulator.clearAll();
        simulator.updateFaults(FaultSettings.none());
        simulator.updateDispatch(new DispatchSettings(Duration.ofSeconds(1), 100, 10, false));
        receiver = new FakeReceiver();
    }

    @AfterEach
    void tearDown() {
        sut.awaitIdle(WAIT);
        receiver.close();
    }

    private Instant expiration() {
        return clock.instant().plus(Duration.ofHours(48));
    }

    @Test
    void 알림_본문에는_택배사와_송장_번호만_담긴다() {
        // given
        simulator.registerWebhook(SILENT, receiver.url(), expiration(), clock.instant());

        // when
        sut.tick();
        boolean idle = sut.awaitIdle(WAIT);

        // then
        assertThat(idle).isTrue();
        assertThat(receiver.bodies()).hasSize(1);
        Map<String, Object> body = JsonPath.read(receiver.bodies().getFirst(), "$");
        assertThat(body).containsOnly(Map.entry("carrierId", CARRIER), Map.entry("trackingNumber", "904000000001"));
    }

    @ParameterizedTest
    @MethodSource("성공_응답")
    void 성공_응답은_성공으로_기록하고_다시_보내지_않는다(int status) {
        // given
        receiver.respondWith(status);
        simulator.registerWebhook(SILENT, receiver.url(), expiration(), clock.instant());

        // when
        sut.tick();
        sut.awaitIdle(WAIT);
        clock.advance(Duration.ofSeconds(10));
        sut.tick();
        sut.awaitIdle(WAIT);

        // then
        List<CallbackRecord> records = simulator.callbackRecords(null, 10);
        assertSoftly(softly -> {
            softly.assertThat(receiver.bodies()).hasSize(1);
            softly.assertThat(records).hasSize(1);
            softly.assertThat(records.getFirst().responseCode()).isEqualTo(status);
            softly.assertThat(records.getFirst().success()).isTrue();
            softly.assertThat(simulator.stats(clock.instant()).total(Metric.CALLBACK_OK)).isEqualTo(1);
        });
    }

    static Stream<Integer> 성공_응답() {
        return Stream.of(200, 202, 204);
    }

    @Test
    void 오류_응답은_실패로_기록하고_1초_뒤에_다시_보낸다() {
        // given
        receiver.respondWith(500);
        simulator.registerWebhook(SILENT, receiver.url(), expiration(), clock.instant());

        // when
        sut.tick();
        sut.awaitIdle(WAIT);
        int afterFirst = receiver.bodies().size();
        clock.advance(Duration.ofSeconds(1));
        sut.tick();
        sut.awaitIdle(WAIT);

        // then
        List<CallbackRecord> records = simulator.callbackRecords(null, 10);
        assertSoftly(softly -> {
            softly.assertThat(afterFirst).isEqualTo(1);
            softly.assertThat(receiver.bodies()).hasSize(2);
            softly.assertThat(records).extracting(CallbackRecord::attemptNo).containsExactly(2, 1);
            softly.assertThat(records).extracting(CallbackRecord::responseCode).containsOnly(500);
            softly.assertThat(records).extracting(CallbackRecord::success).containsOnly(false);
            softly.assertThat(simulator.stats(clock.instant()).total(Metric.CALLBACK_FAIL)).isEqualTo(2);
        });
    }

    @Test
    void 받는_쪽이_1초_안에_답하지_않으면_시간_초과다() {
        // given
        receiver.delay(Duration.ofSeconds(2));
        simulator.registerWebhook(SILENT, receiver.url(), expiration(), clock.instant());

        // when
        sut.tick();
        sut.awaitIdle(WAIT);

        // then
        CallbackRecord record = simulator.callbackRecords(null, 10).getFirst();
        assertSoftly(softly -> {
            softly.assertThat(record.failure()).isEqualTo("시간 초과");
            softly.assertThat(record.responseCode()).isNull();
            softly.assertThat(record.elapsedMillis()).isBetween(900L, 1900L);
        });
    }

    @Test
    void 받는_쪽이_본문을_끝내지_않아도_시간_초과로_끝나고_다음_알림이_나간다() {
        // given: 응답 헤더만 보내고 본문을 10초 동안 끝내지 않는 수신기
        receiver.respondWith(200);
        receiver.stallBody(Duration.ofSeconds(10));
        simulator.registerWebhook(SILENT, receiver.url(), expiration(), clock.instant());

        // when
        sut.tick();
        boolean idle = sut.awaitIdle(Duration.ofSeconds(4));

        // then
        List<CallbackRecord> records = simulator.callbackRecords(null, 10);
        assertSoftly(softly -> {
            softly.assertThat(idle).isTrue();
            softly.assertThat(records).extracting(CallbackRecord::failure).containsExactly("시간 초과");
        });
    }

    @Test
    void 받는_곳이_죽어_있어도_다른_알림은_나간다() {
        // given: 닫아 버린 서버의 주소와 살아 있는 주소
        FakeReceiver dead = new FakeReceiver();
        String deadUrl = dead.url();
        dead.close();
        ParcelKey other = new ParcelKey(CARRIER, "904000000002");
        simulator.registerWebhook(SILENT, deadUrl, expiration(), clock.instant());
        simulator.registerWebhook(other, receiver.url(), expiration(), clock.instant());

        // when
        sut.tick();
        boolean idle = sut.awaitIdle(WAIT);

        // then
        assertSoftly(softly -> {
            softly.assertThat(idle).isTrue();
            softly.assertThat(receiver.bodies()).hasSize(1);
            softly.assertThat(simulator.callbackRecords("904000000001", 10))
                    .extracting(CallbackRecord::failure).containsExactly("연결 실패");
            softly.assertThat(simulator.callbackRecords("904000000002", 10))
                    .extracting(CallbackRecord::success).containsExactly(true);
        });
    }

    @Test
    void 동시에_보내는_수를_넘지_않는다() {
        // given
        receiver.delay(Duration.ofMillis(200));
        simulator.updateDispatch(new DispatchSettings(Duration.ofSeconds(1), 100, 3, false));
        for (int i = 1; i <= 20; i++) {
            simulator.registerWebhook(new ParcelKey(CARRIER, Scenario.NOT_FOUND.trackingNumber(i)), receiver.url(),
                    expiration(), clock.instant());
        }

        // when
        sut.tick();
        boolean idle = sut.awaitIdle(WAIT);

        // then
        assertSoftly(softly -> {
            softly.assertThat(idle).isTrue();
            softly.assertThat(receiver.bodies()).hasSize(20);
            softly.assertThat(receiver.maxConcurrent()).isBetween(2, 3);
        });
    }

    @Test
    void 앞서_꺼낸_묶음이_끝나기_전에는_새로_꺼내지_않는다() {
        // given: 한 번에 한 건씩, 받는 쪽은 300ms 걸린다
        receiver.delay(Duration.ofMillis(300));
        simulator.updateDispatch(new DispatchSettings(Duration.ofSeconds(1), 1, 10, false));
        simulator.registerWebhook(SILENT, receiver.url(), expiration(), clock.instant());
        simulator.registerWebhook(new ParcelKey(CARRIER, "904000000002"), receiver.url(), expiration(), clock.instant());

        // when: 첫 건이 끝나기 전에 간격이 지나 다시 tick이 온다
        sut.tick();
        clock.advance(Duration.ofSeconds(1));
        sut.tick();
        long pendingWhileSending = simulator.stats(clock.instant()).pendingCallbacks();
        sut.awaitIdle(WAIT);
        sut.tick();
        sut.awaitIdle(WAIT);

        // then
        assertSoftly(softly -> {
            softly.assertThat(pendingWhileSending).isEqualTo(1);
            softly.assertThat(receiver.bodies()).hasSize(2);
        });
    }

    @ParameterizedTest
    @MethodSource("보낼_수_없는_주소")
    void 보낼_수_없는_주소는_예외_없이_실패로_돌려준다(String callbackUrl) {
        // when
        CallbackOutcome outcome = sender.send(new CallbackAttempt(1, SILENT, callbackUrl, 1));

        // then
        assertSoftly(softly -> {
            softly.assertThat(outcome.success()).isFalse();
            softly.assertThat(outcome.failure()).isEqualTo("잘못된 주소");
        });
    }

    static Stream<String> 보낼_수_없는_주소() {
        return Stream.of("http://exa mple.test/callback", "localhost:8080/callback", "ftp://example.test/callback", "");
    }

    @Test
    void 송장_번호에_따옴표가_있어도_본문은_올바른_JSON이다() {
        // given
        CallbackAttempt attempt = new CallbackAttempt(1, new ParcelKey("kr.\"cj\"", "12\\34\n56"), receiver.url(), 1);

        // when
        String body = CallbackSender.body(attempt);

        // then
        assertSoftly(softly -> {
            softly.assertThat(JsonPath.<String>read(body, "$.carrierId")).isEqualTo("kr.\"cj\"");
            softly.assertThat(JsonPath.<String>read(body, "$.trackingNumber")).isEqualTo("12\\34\n56");
        });
    }
}
