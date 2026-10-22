package com.ok.common.trace;

import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 발행 스레드의 traceId가 비동기 리스너 스레드로 전달되는지 확인한다
class TraceEventIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TraceProbeListener probe;

    @Test
    void 발행_스레드의_traceId가_리스너_스레드로_전파된다() {
        // given
        String key = "propagate-" + UUID.randomUUID();

        // when
        try (TraceContext.Scope ignored = TraceContext.open("trace-propagate")) {
            publishInTransaction(new TraceProbeEvent(key));
        }

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(probe.observed(key)).containsExactly("trace-propagate"));
        await().atMost(TIMEOUT).until(() -> countCompletedPublications(key) == 1);
    }

    @Test
    void traceId가_없어도_리스너는_정상_실행되고_MDC는_비어_있다() {
        // given
        String key = "no-trace-" + UUID.randomUUID();

        // when
        publishInTransaction(new TraceProbeEvent(key));

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(probe.observed(key)).containsExactly("null"));
        await().atMost(TIMEOUT).until(() -> countCompletedPublications(key) == 1);
    }

    private void publishInTransaction(Object event) {
        // 커밋되어야 @ApplicationModuleListener가 실행된다
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    private int countCompletedPublications(String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE status = ? AND serialized_event LIKE ?",
                Integer.class,
                "COMPLETED", "%" + key + "%");
        return count == null ? 0 : count;
    }
}
