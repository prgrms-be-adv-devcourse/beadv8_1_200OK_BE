package com.ok.common.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// 리스너가 실행될 때 보이는 traceId를 기록한다. 통합 테스트가 컴포넌트 스캔으로 함께 띄운다
// 같은 모듈(common) 안의 이벤트만 받으므로 다른 모듈 경계에 영향을 주지 않는다
@Component
class TraceProbeListener {

    private static final Logger log = LoggerFactory.getLogger(TraceProbeListener.class);

    private final Map<String, List<String>> observed = new ConcurrentHashMap<>();
    private final Set<String> failOnce = ConcurrentHashMap.newKeySet();

    @ApplicationModuleListener
    void on(TraceProbeEvent event) {
        // 로그 패턴에 traceId가 찍히는지 눈으로 확인하는 용도
        log.info("프로브 리스너 실행 key={}", event.key());
        observed.computeIfAbsent(event.key(), key -> new CopyOnWriteArrayList<>())
                .add(String.valueOf(MDC.get(TraceContext.MDC_KEY)));
        if (failOnce.remove(event.key())) {
            throw new IllegalStateException("재처리 검증을 위한 의도된 실패");
        }
    }

    void failOnceFor(String key) {
        failOnce.add(key);
    }

    List<String> observed(String key) {
        return observed.getOrDefault(key, List.of());
    }
}
