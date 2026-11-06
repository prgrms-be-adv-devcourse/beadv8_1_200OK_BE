package com.ok.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.mock.env.MockEnvironment;

import ch.qos.logback.classic.LoggerContext;
import net.logstash.logback.argument.StructuredArguments;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 {@code logback-spring.xml}을 Boot의 로깅 시스템으로 읽어, 파일에 나온 JSON을 검증한다.
 * 비동기 appender는 컨텍스트를 멈춰야 큐를 비우므로 로그를 남긴 뒤 {@link #flush()}로 멈추고 파일을 읽는다.
 */
class LogbackSpringConfigTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path logDir;

    private LoggingSystem loggingSystem;

    @BeforeEach
    void setUp() {
        LogDropCounter.reset();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.logging.dir", logDir.toString())
                .withProperty("spring.application.name", "paldo-test");
        loggingSystem = LoggingSystem.get(getClass().getClassLoader());
        // 앞선 Spring 테스트가 이미 로깅을 초기화했으면 Boot는 다시 초기화하지 않는다. 초기화 표시를 지워야 이 테스트의 설정이 적용된다
        loggingSystem.cleanUp();
        loggingSystem.beforeInitialize();
        loggingSystem.initialize(new LoggingInitializationContext(environment), "classpath:logback-spring.xml", null);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        loggingSystem.cleanUp();
    }

    @Test
    void 성공_로그는_고정_스키마_JSON_한_줄로_app_log에_남고_실패_파일에는_없다() throws IOException {
        // given
        Logger log = LoggerFactory.getLogger("com.ok.order.service.OrderService");
        MDC.put("traceId", "111");
        MDC.put("orderId", "1024");
        MDC.put("shipmentId", "7");
        MDC.put("eventType", "STATE_CHANGED");
        MDC.put("step", "ORDER_CREATE");
        MDC.put("outcome", "SUCCESS");

        // when
        log.info("주문을 생성했다");
        flush();

        // then
        List<String> lines = readLines("app.log");
        assertThat(lines).hasSize(1);
        JsonNode json = MAPPER.readTree(lines.get(0));
        assertSoftly(softly -> {
            softly.assertThat(json.get("timestamp").asString()).isNotBlank();
            softly.assertThat(json.get("level").asString()).isEqualTo("INFO");
            softly.assertThat(json.get("app").asString()).isEqualTo("paldo-test");
            softly.assertThat(json.get("module").asString()).isEqualTo("order");
            softly.assertThat(json.get("eventType").asString()).isEqualTo("STATE_CHANGED");
            softly.assertThat(json.get("step").asString()).isEqualTo("ORDER_CREATE");
            softly.assertThat(json.get("traceId").asString()).isEqualTo("111");
            softly.assertThat(json.get("orderId").asString()).isEqualTo("1024");
            softly.assertThat(json.get("shipmentId").asString()).isEqualTo("7");
            softly.assertThat(json.get("outcome").asString()).isEqualTo("SUCCESS");
            softly.assertThat(json.get("message").asString()).isEqualTo("주문을 생성했다");
            softly.assertThat(json.has("error")).isFalse();
            softly.assertThat(json.propertyNames()).as("스키마 밖의 필드(컨텍스트 속성 등)가 없다").containsExactlyInAnyOrder(
                    "timestamp", "level", "app", "module", "logger", "thread", "eventType", "step", "traceId", "orderId",
                    "shipmentId", "outcome", "message");
            softly.assertThat(readLines("app-error.log")).isEmpty();
        });
    }

    @Test
    void WARN_이상은_서로_다른_키_수백_건도_유실_없이_실패_파일에_남고_성공_파일에는_없다() throws IOException {
        // given
        int count = 500;

        // when
        for (int i = 0; i < count; i++) {
            LoggerFactory.getLogger("com.ok.wallet.Failure" + i).warn("실패 {}", i);
            LoggerFactory.getLogger("com.ok.wallet.Failure" + i).error("오류 {}", i);
        }
        flush();

        // then
        assertSoftly(softly -> {
            softly.assertThat(readLines("app-error.log")).hasSize(count * 2);
            softly.assertThat(readLines("app.log")).isEmpty();
            softly.assertThat(LogDropCounter.count(LogDropCounter.Reason.THROTTLED)).isZero();
        });
    }

    @Test
    void 개인정보_키의_필드_값은_가려지고_스키마_필드는_가려지지_않는다() throws IOException {
        // given
        Logger log = LoggerFactory.getLogger("com.ok.delivery.DeliveryService");
        MDC.put("traceId", "222");

        // when
        log.atInfo()
                .addKeyValue("receiverName", "홍길동")
                .addKeyValue("buyerPhone", "010-1234-5678")
                .addKeyValue("orderId", "1")
                .log("배송 요청 완료");
        log.info("구조화 인자", StructuredArguments.kv("phone", "010-9999-0000"));
        flush();

        // then
        String all = String.join("\n", readLines("app.log"));
        assertSoftly(softly -> {
            softly.assertThat(all).doesNotContain("홍길동", "010-1234-5678", "010-9999-0000");
            softly.assertThat(all).contains("\"receiverName\":\"****\"", "\"buyerPhone\":\"****\"");
            softly.assertThat(all).contains("\"traceId\":\"222\"", "\"logger\":\"com.ok.delivery.DeliveryService\"");
        });
    }

    @Test
    void 메시지와_예외_메시지_안의_문장은_가리지_않아_장애_원인이_남는다() throws IOException {
        // given
        Logger log = LoggerFactory.getLogger("com.ok.common.CauseTest");

        // when
        log.warn("서버 hostname=web-1 연결 실패", new IllegalStateException("javax.naming.NameNotFoundException: 원인 메시지"));
        flush();

        // then
        String all = String.join("\n", readLines("app-error.log"));
        assertThat(all).contains("hostname=web-1", "NameNotFoundException: 원인 메시지");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void 공백_없는_긴_문자열_한_줄도_바로_기록되고_1KB로_잘린다() throws IOException {
        // given
        Logger log = LoggerFactory.getLogger("com.ok.common.LongInputTest");

        // when
        log.warn("a".repeat(80_000));
        flush();

        // then
        JsonNode warn = MAPPER.readTree(readLines("app-error.log").get(0));
        assertThat(warn.get("message").asString().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(1024);
    }

    @Test
    void 필드는_1KB로_잘리고_스택_트레이스는_깊이_30으로_잘린다() throws IOException {
        // given
        Logger log = LoggerFactory.getLogger("com.ok.common.LimitTest");
        String longMessage = "가".repeat(3000);

        // when
        log.info(longMessage);
        log.warn("깊은 예외", deepException(100));
        flush();

        // then
        JsonNode info = MAPPER.readTree(readLines("app.log").get(0));
        JsonNode warn = MAPPER.readTree(readLines("app-error.log").get(0));
        String stack = warn.get("error").asString();
        long frames = stack.lines().filter(line -> line.startsWith("\tat ")).count();
        assertSoftly(softly -> {
            softly.assertThat(info.get("message").asString().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(1024);
            softly.assertThat(info.get("message").asString()).endsWith("…[잘림]");
            softly.assertThat(frames).isLessThanOrEqualTo(30);
            softly.assertThat(stack.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(6144);
        });
    }

    @Test
    void 한글_예외_메시지가_길어도_error_필드는_스택_상한_바이트_안에서_남는다() throws IOException {
        // given
        Logger log = LoggerFactory.getLogger("com.ok.common.KoreanStackTest");

        // when
        log.warn("실패", new IllegalStateException("가".repeat(6000)));
        flush();

        // then
        JsonNode json = MAPPER.readTree(readLines("app-error.log").get(0));
        assertThat(json.get("error").asString().getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(6144);
    }

    @Test
    void 이벤트가_8KB를_넘으면_본문을_생략한_짧은_이벤트로_대신하고_흐름_키는_남긴다() throws IOException {
        // given
        Logger log = LoggerFactory.getLogger("com.ok.common.EventLimitTest");
        MDC.put("traceId", "333");
        var builder = log.atInfo();
        for (int i = 0; i < 20; i++) {
            builder = builder.addKeyValue("field" + i, "x".repeat(900));
        }

        // when
        builder.log("필드가 많은 이벤트");
        flush();

        // then
        String line = readLines("app.log").get(0);
        JsonNode json = MAPPER.readTree(line);
        assertSoftly(softly -> {
            softly.assertThat(line.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(8192);
            softly.assertThat(json.get("message").asString()).contains("이벤트가 상한(8192바이트)을 넘어 본문을 생략했다");
            softly.assertThat(json.get("traceId").asString()).isEqualTo("333");
            softly.assertThat(json.has("field0")).isFalse();
        });
    }

    private void flush() {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
    }

    private List<String> readLines(String fileName) {
        Path file = logDir.resolve(fileName);
        try {
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Throwable deepException(int depth) {
        return depth == 0 ? new IllegalStateException("깊은 예외") : deepException(depth - 1);
    }
}
