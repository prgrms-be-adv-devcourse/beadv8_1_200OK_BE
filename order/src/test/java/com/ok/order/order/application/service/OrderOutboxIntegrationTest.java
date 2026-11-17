package com.ok.order.order.application.service;

import com.ok.order.order.adapter.out.persistence.OrderOutboxJpaRepository;
import com.ok.order.order.application.port.in.OrderOutboxRecordUseCase;
import com.ok.order.order.application.port.in.OrderOutboxRelayUseCase;
import com.ok.order.order.domain.OrderOutbox;
import com.ok.order.order.domain.OrderOutboxStatus;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.OrderKafkaTestConfiguration;
import com.ok.testsupport.OrderKafkaTopicReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 주문 Kafka 발행 기반(#666)을 테스트 토픽과 실제 Kafka(Testcontainers)로 확인한다.
// - 재발송 스케줄러는 module-test.yml에서 꺼 두고, 재발송은 테스트가 직접 부른다
// - 다른 테스트의 메시지와 섞이지 않게 테스트마다 다른 키로 골라 읽는다
class OrderOutboxIntegrationTest extends AbstractIntegrationTest {

    private static final String TOPIC = OrderKafkaTestConfiguration.OUTBOX_TOPIC;
    // 다시 보낸 중복까지 세려고 이 시간 동안 계속 읽는다. 발송은 저장 확인까지 기다린 뒤 끝나므로 정상이면 바로 읽힌다
    private static final Duration READ_DURATION = Duration.ofSeconds(3);
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    OrderOutboxRecordUseCase sut;

    @Autowired
    OrderOutboxRelayUseCase orderOutboxRelayUseCase;

    @Autowired
    OrderOutboxJpaRepository orderOutboxRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    private OrderKafkaTopicReader reader;
    private String key;

    @BeforeEach
    void setUp() {
        reader = new OrderKafkaTopicReader(kafkaConnectionDetails.getBootstrapServers(), TOPIC);
        key = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        reader.close();
        orderOutboxRepository.deleteAllInBatch();
    }

    @Test
    void 커밋되면_바로_키와_JSON_객체로_한_건_나가고_줄이_보냄이_된다() {
        // when
        transactionTemplate.executeWithoutResult(status -> sut.record(TOPIC, key, "{\"orderId\":10,\"name\":\"사과\"}"));

        // then: 문자열로 한 번 더 감싸지 않은 JSON 객체 그대로 나간다
        List<String> values = reader.readValuesFor(key, READ_DURATION);
        assertThat(values).containsExactly("{\"orderId\":10,\"name\":\"사과\"}");
        assertThat(waitFor(() -> statusOf(key) == OrderOutboxStatus.SENT)).isTrue();
    }

    @Test
    void 트랜잭션이_롤백되면_줄도_메시지도_남지_않는다() {
        // when
        transactionTemplate.executeWithoutResult(status -> {
            sut.record(TOPIC, key, "{\"orderId\":11}");
            status.setRollbackOnly();
        });

        // then
        assertSoftly(softly -> {
            softly.assertThat(reader.readValuesFor(key, READ_DURATION)).isEmpty();
            softly.assertThat(orderOutboxRepository.findAll()).extracting(OrderOutbox::getMessageKey).doesNotContain(key);
        });
    }

    // 주문 변경과 따로 커밋되면 롤백된 변경의 메시지가 나가거나 커밋된 변경의 메시지가 안 나갈 수 있어 막는다
    @Test
    void 트랜잭션_밖에서_남기면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> sut.record(TOPIC, key, "{\"orderId\":12}"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 커밋_직후_보내지_못하고_남은_줄은_재발송이_쌓인_순서대로_보내고_보냄으로_바꾼다() {
        // given: 커밋 직후 발송 없이 보낼 차례로 남은 줄 두 개(같은 키)
        orderOutboxRepository.save(OrderOutbox.pending(TOPIC, key, "{\"seq\":1}"));
        orderOutboxRepository.save(OrderOutbox.pending(TOPIC, key, "{\"seq\":2}"));

        // when
        int sent = orderOutboxRelayUseCase.relayPending(100);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sent).isGreaterThanOrEqualTo(2);
            softly.assertThat(reader.readValuesFor(key, READ_DURATION)).containsExactly("{\"seq\":1}", "{\"seq\":2}");
            softly.assertThat(orderOutboxRepository.findAll()).filteredOn(outbox -> key.equals(outbox.getMessageKey()))
                    .extracting(OrderOutbox::getStatus).containsOnly(OrderOutboxStatus.SENT);
        });
    }

    private OrderOutboxStatus statusOf(String messageKey) {
        return orderOutboxRepository.findAll().stream()
                .filter(outbox -> messageKey.equals(outbox.getMessageKey()))
                .map(OrderOutbox::getStatus)
                .findFirst()
                .orElse(null);
    }

    // 커밋 직후 발송은 다른 스레드에서 돌아 "보냄" 기록이 조금 늦을 수 있어 기다린다
    private static boolean waitFor(Supplier<Boolean> condition) {
        Instant until = Instant.now().plus(WAIT_TIMEOUT);
        while (Instant.now().isBefore(until)) {
            if (condition.get()) {
                return true;
            }
            sleep();
        }
        return condition.get();
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
