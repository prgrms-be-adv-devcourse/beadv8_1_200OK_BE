package com.ok.common.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 작업을 넘기는 스레드의 MDC를 비동기 작업을 실행하는 스레드로 있는 그대로 복사한다.
 *
 * <p>MDC는 스레드마다 따로라서, 복사하지 않으면 비동기로 실행된 코드의 로그에서 호출한 쪽의 MDC 값이 사라진다.
 * 이 클래스는 MDC에 이미 있는 값을 옮기는 통로일 뿐이고, TraceID를 만들어 MDC에 넣는 일(요청 필터 등)은 하지 않는다.
 * 작업이 끝나면 실행한 스레드의 원래 MDC로 되돌려, 스레드를 재사용하는 실행기에서도 값이 다음 작업으로 새지 않게 한다.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 이 메서드는 작업을 넘기는 스레드에서 실행되므로 여기서 복사해야 호출한 쪽의 MDC를 얻는다
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            restore(captured);
            try {
                runnable.run();
            } finally {
                restore(previous);
            }
        };
    }

    private static void restore(Map<String, String> contextMap) {
        if (contextMap == null) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(contextMap);
    }
}
