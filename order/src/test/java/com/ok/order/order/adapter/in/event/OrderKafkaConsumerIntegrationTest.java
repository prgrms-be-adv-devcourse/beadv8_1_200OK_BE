package com.ok.order.order.adapter.in.event;

import com.ok.order.OrderSchema;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.OrderKafkaTestConfiguration;
import com.ok.testsupport.OrderKafkaTestListener;
import com.ok.testsupport.OrderKafkaTestMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 주문 Kafka 수신 기반(#666)을 테스트 토픽·테스트 리스너와 실제 Kafka(Testcontainers)로 확인한다.
// 기록마저 실패할 때 같은 메시지를 다시 받는 동작은 OrderKafkaConsumerConfigTest가 Kafka 없이 확인한다
class OrderKafkaConsumerIntegrationTest extends AbstractIntegrationTest {

    private static final String TOPIC = OrderKafkaTestConfiguration.LISTENER_TOPIC;
    // 다시 시도(1·2·4초)를 다 쓰고 기록될 때까지 넉넉히 기다린다
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    OrderKafkaTestListener listener;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final List<String> keys = new ArrayList<>();

    @AfterEach
    void tearDown() {
        keys.forEach(key -> jdbcTemplate.update(
                "DELETE FROM " + OrderSchema.NAME + ".order_message_failure WHERE message_key = ?", key));
    }

    @Test
    void 값이_틀린_메시지는_다시_시도하지_않고_원문과_그룹을_바로_기록한다() {
        // given
        String id = newKey();

        // when
        send(id, "invalid");

        // then
        assertThat(waitFor(() -> failureOf(id) != null)).isTrue();
        Map<String, Object> sut = failureOf(id);
        assertSoftly(softly -> {
            softly.assertThat(listener.attemptsOf(id)).isEqualTo(1);
            softly.assertThat(sut.get("consumer_group")).isEqualTo(OrderKafkaTestConfiguration.LISTENER_GROUP);
            softly.assertThat(sut.get("topic")).isEqualTo(TOPIC);
            softly.assertThat((String) sut.get("payload")).contains("\"id\":\"" + id + "\"");
            softly.assertThat((String) sut.get("failure_reason")).startsWith("IllegalArgumentException");
            softly.assertThat(sut.get("status")).isEqualTo("PENDING");
        });
    }

    @Test
    void 계속_실패하는_메시지는_세_번_다시_시도한_뒤_기록하고_다음_메시지는_처리한다() {
        // given
        String failing = newKey();
        String next = newKey();

        // when
        send(failing, "fail");
        send(next, "ok");

        // then: 처음 1번 + 다시 시도 3번
        assertThat(waitFor(() -> listener.isProcessed(next))).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(failureOf(failing)).isNotNull();
            softly.assertThat(listener.attemptsOf(failing)).isEqualTo(4);
        });
    }

    @Test
    void 한_번_실패한_뒤_다시_시도에서_성공하면_기록하지_않는다() {
        // given
        String id = newKey();

        // when
        send(id, "flaky");

        // then
        assertThat(waitFor(() -> listener.isProcessed(id))).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(listener.attemptsOf(id)).isEqualTo(2);
            softly.assertThat(failureOf(id)).isNull();
        });
    }

    @Test
    void JSON으로_읽지_못한_메시지는_받은_그대로의_원문을_기록한다() {
        // given: 객체가 아니라 JSON 문자열이라 메시지 타입으로 읽지 못한다
        String id = newKey();

        // when
        kafkaTemplate.send(TOPIC, id, "객체가 아닌 값");

        // then
        assertThat(waitFor(() -> failureOf(id) != null)).isTrue();
        assertThat((String) failureOf(id).get("payload")).isEqualTo("\"객체가 아닌 값\"");
    }

    private String newKey() {
        String key = UUID.randomUUID().toString();
        keys.add(key);
        return key;
    }

    private void send(String id, String mode) {
        kafkaTemplate.send(TOPIC, id, new OrderKafkaTestMessage(id, mode));
    }

    private Map<String, Object> failureOf(String key) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM " + OrderSchema.NAME + ".order_message_failure WHERE message_key = ?", key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static boolean waitFor(Supplier<Boolean> condition) {
        Instant until = Instant.now().plus(WAIT_TIMEOUT);
        while (Instant.now().isBefore(until)) {
            if (condition.get()) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return condition.get();
    }
}
