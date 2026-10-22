package com.ok.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class TraceAwareEventSerializerTest {

    record SampleEvent(Long id, String name, LocalDateTime occurredAt) {
    }

    record AmountEvent(BigDecimal amount) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
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
            softly.assertThat(TraceContext.takePending()).isEqualTo("trace-1");
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
            softly.assertThat(TraceContext.takePending()).isNull();
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
        assertThat(TraceContext.takePending()).isNull();
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
            softly.assertThat(TraceContext.takePending()).isNull();
        });
    }
}
