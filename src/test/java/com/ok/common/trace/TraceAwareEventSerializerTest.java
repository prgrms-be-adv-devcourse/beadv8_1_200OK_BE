package com.ok.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class TraceAwareEventSerializerTest {

    record SampleEvent(Long id, String name, LocalDateTime occurredAt) {
    }

    record AmountEvent(BigDecimal amount) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicReference<Object> reserialized = new AtomicReference<>();
    private final TraceAwareEventSerializer sut = new TraceAwareEventSerializer(mapper);

    @AfterEach
    void tearDown() {
        MDC.clear();
        TraceContext.clearPending();
    }

    @Test
    void traceId가_있으면_직렬화_결과에_trace_필드가_들어간다() {
        // given
        SampleEvent event = new SampleEvent(1L, "가입", LocalDateTime.of(2026, 10, 14, 9, 30));

        // when
        String json;
        try (TraceContext.Scope ignored = TraceContext.open("trace-1")) {
            json = (String) sut.serialize(event);
        }

        // then
        assertThat(mapper.readTree(json).get("_trace").stringValue()).isEqualTo("trace-1");
    }

    @Test
    void traceId가_없으면_trace_필드를_쓰지_않는다() {
        // given
        SampleEvent event = new SampleEvent(1L, "가입", LocalDateTime.of(2026, 10, 14, 9, 30));

        // when
        String json = (String) sut.serialize(event);

        // then
        assertThat(mapper.readTree(json).has("_trace")).isFalse();
    }

    @Test
    void 직렬화한_이벤트를_그대로_복원하고_원래_traceId를_임시_보관한다() {
        // given
        SampleEvent event = new SampleEvent(1L, "가입", LocalDateTime.of(2026, 10, 14, 9, 30));
        Object serialized;
        try (TraceContext.Scope ignored = TraceContext.open("trace-1")) {
            serialized = sut.serialize(event);
        }

        // when
        SampleEvent restored = sut.deserialize(serialized, SampleEvent.class);

        // then
        assertThat(restored).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(restored).isEqualTo(event);
            softly.assertThat(TraceContext.takePending()).isEqualTo(new TraceContext.PendingTrace("trace-1"));
        });
    }

    @Test
    void trace_필드가_없는_기존_행도_복원되고_임시_보관은_비어_있다() {
        // given
        String legacyJson = "{\"id\":7,\"name\":\"기존\",\"occurredAt\":\"2026-10-01T00:00:00\"}";

        // when
        SampleEvent restored = sut.deserialize(legacyJson, SampleEvent.class);

        // then
        assertThat(restored).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(restored.id()).isEqualTo(7L);
            softly.assertThat(TraceContext.takePending()).isEqualTo(new TraceContext.PendingTrace(null));
        });
    }

    @Test
    void 앞선_이벤트가_남긴_임시_값을_다음_이벤트가_물려받지_않는다() {
        // given
        TraceContext.setPending("stale-trace");
        String legacyJson = "{\"id\":7,\"name\":\"기존\",\"occurredAt\":\"2026-10-01T00:00:00\"}";

        // when
        sut.deserialize(legacyJson, SampleEvent.class);

        // then
        assertThat(TraceContext.takePending()).isEqualTo(new TraceContext.PendingTrace(null));
    }

    @Test
    void 소수_값을_정밀도_손실_없이_복원한다() {
        // given
        AmountEvent event = new AmountEvent(new BigDecimal("12345678901234567.89"));
        Object serialized;
        try (TraceContext.Scope ignored = TraceContext.open("trace-1")) {
            serialized = sut.serialize(event);
        }

        // when
        AmountEvent restored = sut.deserialize(serialized, AmountEvent.class);

        // then
        assertThat(restored).isNotNull();
        assertThat(restored.amount()).isEqualByComparingTo("12345678901234567.89");
    }

    @Test
    void trace_필드가_문자열이_아니면_무시한다() {
        // given
        String json = "{\"id\":7,\"name\":\"기존\",\"occurredAt\":\"2026-10-01T00:00:00\",\"_trace\":123}";

        // when
        SampleEvent restored = sut.deserialize(json, SampleEvent.class);

        // then
        assertThat(restored).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(restored.id()).isEqualTo(7L);
            softly.assertThat(TraceContext.takePending()).isEqualTo(new TraceContext.PendingTrace(null));
        });
    }

    // Modulith는 완료 표시를 serialized_event 문자열 일치로 찾기도 한다. 리스너 스레드의 재직렬화 결과가 저장값과 같아야 한다
    @Test
    void 재처리_리스너_스레드의_재직렬화_결과는_저장된_문자열과_같다() throws Exception {
        // given
        SampleEvent event = new SampleEvent(1L, "가입", LocalDateTime.of(2026, 10, 14, 9, 30));
        Object stored;
        try (TraceContext.Scope ignored = TraceContext.open("T")) {
            stored = sut.serialize(event);
        }

        // when
        Object reserialized = reserializeInListenerThread(stored, event);

        // then
        assertThat(reserialized).isEqualTo(stored);
    }

    @Test
    void trace_없이_저장된_이벤트도_제출_스레드_traceId와_무관하게_저장된_문자열과_같다() throws Exception {
        // given
        SampleEvent event = new SampleEvent(1L, "가입", LocalDateTime.of(2026, 10, 14, 9, 30));
        Object stored = sut.serialize(event);

        // when
        Object reserialized = reserializeInListenerThread(stored, event);

        // then
        assertThat(reserialized).isEqualTo(stored);
    }

    // 제출 스레드 MDC를 "S"로 둔 채 역직렬화하고 데코레이트한 작업을 제출해, 작업 안에서 다시 직렬화한 결과를 돌려준다
    private Object reserializeInListenerThread(Object stored, SampleEvent event) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            MDC.put(TraceContext.MDC_KEY, "S");
            sut.deserialize(stored, SampleEvent.class);
            Runnable decorated = new TraceTaskDecorator().decorate(() -> reserialized.set(sut.serialize(event)));
            executor.submit(decorated).get();
            return reserialized.get();
        } finally {
            executor.shutdownNow();
        }
    }
}
