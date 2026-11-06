package com.ok.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LoggingConfigTest {

    @Test
    void 버린_로그_건수가_사유별_지표로_노출된다() {
        // given
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new LoggingConfig().logDropMetrics().bindTo(registry);
        long queueFullBefore = LogDropCounter.count(LogDropCounter.Reason.QUEUE_FULL);

        // when
        LogDropCounter.increment(LogDropCounter.Reason.QUEUE_FULL, 3);

        // then
        assertThat(registry.get("app.log.dropped").tag("reason", "queue_full").functionCounter().count())
                .isEqualTo(queueFullBefore + 3);
        assertThat(registry.get("app.log.dropped").tag("reason", "throttled").functionCounter()).isNotNull();
        assertThat(registry.get("app.log.dropped").tag("reason", "sampled").functionCounter()).isNotNull();
    }
}
