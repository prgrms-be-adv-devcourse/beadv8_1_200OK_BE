package com.ok.common.trace;

import org.springframework.modulith.events.core.EventSerializer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 이벤트를 JSON으로 저장할 때 현재 traceId를 {@code _trace} 필드로 함께 넣고, 읽을 때 꺼낸다.
 * 재처리(resubmit)는 {@code event_publication}에서 이벤트를 다시 읽는 것이 전부라서, 원래 traceId를 살리려면 여기에 담아야 한다.
 */
public class TraceAwareEventSerializer implements EventSerializer {

    static final String TRACE_FIELD = "_trace";

    private final JsonMapper mapper;

    public TraceAwareEventSerializer(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Object serialize(Object event) {
        String traceId = TraceContext.current().orElse(null);
        if (traceId == null) {
            return mapper.writeValueAsString(event);
        }

        JsonNode tree = mapper.valueToTree(event);
        if (tree instanceof ObjectNode node) {
            node.put(TRACE_FIELD, traceId);
            return mapper.writeValueAsString(node);
        }
        return mapper.writeValueAsString(event);
    }

    @Override
    public <T> T deserialize(Object serialized, Class<T> type) {
        // 읽기에 실패해도 앞 행이 남긴 값이 이 이벤트의 제출에 붙지 않도록 먼저 비운다
        TraceContext.clearPending();
        String json = serialized.toString();

        // 이벤트 객체는 원래 문자열을 그대로 읽는다. readTree를 거쳐 treeToValue로 바꾸면 소수가 double을 거쳐
        // BigDecimal 정밀도를 잃는다. Modulith 기본 직렬화기와 같은 읽기 방식이고, 이벤트 record가 모르는 _trace는 무시한다
        T event = mapper.readerFor(type)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(json);

        // 값이 없으면(배포 전에 저장된 행) null을 "traceId 없음" 표시로 보관한다. 앞선 이벤트의 값도 물려받지 않는다
        TraceContext.setPending(readTraceId(json));
        return event;
    }

    private String readTraceId(String json) {
        if (mapper.readTree(json) instanceof ObjectNode node) {
            JsonNode trace = node.get(TRACE_FIELD);
            if (trace != null && trace.isString()) {
                return trace.stringValue();
            }
        }
        return null;
    }
}
