package com.ok.common.trace;

import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
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
    private IncompleteEventPublications incompleteEventPublications;

    @Autowired
    private TraceProbeListener probe;

    @AfterEach
    void tearDown() {
        // 재처리 필터가 다른 행을 역직렬화하면서 이 스레드에 남긴 임시 traceId가 다음 테스트에 붙지 않도록 비운다
        TraceContext.clearPending();
        MDC.clear();
    }

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
        // 리스너 완료 처리(발행 기록 COMPLETED)까지 끝난 뒤 마쳐, 미완료 기록이 다른 테스트에 남지 않고 직렬화기 교체 때의 완료 처리 회귀도 잡는다
        await().atMost(TIMEOUT).until(() -> countPublications(key, "COMPLETED") == 1);
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
        // 리스너 완료 처리(발행 기록 COMPLETED)까지 끝난 뒤 마쳐, 미완료 기록이 다른 테스트에 남지 않고 직렬화기 교체 때의 완료 처리 회귀도 잡는다
        await().atMost(TIMEOUT).until(() -> countPublications(key, "COMPLETED") == 1);
    }

    @Test
    void 실패한_이벤트를_재처리하면_원래_traceId로_복원된다() {
        // given: 첫 시도는 실패하도록 해 두고, traceId가 있는 상태에서 발행한다
        String key = "resubmit-" + UUID.randomUUID();
        probe.failOnceFor(key);
        try (TraceContext.Scope ignored = TraceContext.open("trace-resubmit")) {
            publishInTransaction(new TraceProbeEvent(key));
        }
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(probe.observed(key)).containsExactly("trace-resubmit"));
        await().atMost(TIMEOUT).until(() -> countPublications(key, "FAILED") == 1);
        assertThat(storedTrace(key)).isEqualTo("trace-resubmit");

        // when: 재처리는 요청과 무관한 스레드(traceId 없음)에서 일어난다
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
        resubmit(key);

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(probe.observed(key)).containsExactly("trace-resubmit", "trace-resubmit"));
        await().atMost(TIMEOUT).until(() -> countPublications(key, "COMPLETED") == 1);
    }

    @Test
    void traceId_없이_저장된_이벤트를_traceId가_있는_스레드에서_재처리해도_저장값과_같게_처리되고_완료된다() {
        // given: traceId 없이 발행해 첫 시도를 실패시킨다
        String key = "resubmit-no-trace-" + UUID.randomUUID();
        probe.failOnceFor(key);
        publishInTransaction(new TraceProbeEvent(key));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(probe.observed(key)).containsExactly("null"));
        await().atMost(TIMEOUT).until(() -> countPublications(key, "FAILED") == 1);

        // when: 재처리하는 스레드에는 다른 traceId가 있다
        try (TraceContext.Scope ignored = TraceContext.open("resubmitter")) {
            resubmit(key);
        }

        // then: 리스너는 제출 스레드의 traceId를 물려받지 않고, 완료 표시도 된다
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(probe.observed(key)).containsExactly("null", "null"));
        await().atMost(TIMEOUT).until(() -> countPublications(key, "COMPLETED") == 1);
    }

    // 필터 안의 getEvent()가 행을 역직렬화하며 pending을 남기고, Modulith가 행마다 필터 직후에 처리를 제출한다.
    // getEvent() 결과는 캐시되므로 일치한 행의 pending이 그 행의 제출에 그대로 쓰인다
    private void resubmit(String key) {
        incompleteEventPublications.resubmitIncompletePublications(publication ->
                publication.getEvent() instanceof TraceProbeEvent event && event.key().equals(key));
    }

    private void publishInTransaction(Object event) {
        // 커밋되어야 @ApplicationModuleListener가 실행된다
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
    }

    private int countPublications(String key, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE status = ? AND serialized_event LIKE ?",
                Integer.class,
                status, "%" + key + "%");
        return count == null ? 0 : count;
    }

    private String storedTrace(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(serialized_event, '$._trace')) FROM event_publication " +
                        "WHERE serialized_event LIKE ?",
                String.class, "%" + key + "%");
    }
}
