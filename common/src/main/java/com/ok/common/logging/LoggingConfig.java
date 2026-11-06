package com.ok.common.logging;

import java.util.Locale;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 로그 폭증 방지 장치가 버린 로그 건수를 {@code app.log.dropped} 지표로 노출한다. 사유는 {@code reason} 태그다.
 */
@Configuration(proxyBeanMethods = false)
public class LoggingConfig {

    @Bean
    public MeterBinder logDropMetrics() {
        return registry -> {
            for (LogDropCounter.Reason reason : LogDropCounter.Reason.values()) {
                FunctionCounter.builder("app.log.dropped", reason, LogDropCounter::count)
                        .description("버린 로그 건수. queue_full은 큐 초과, throttled는 같은 에러 억제, sampled는 HTTP 성공 샘플링")
                        .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                        .register(registry);
            }
        };
    }
}
