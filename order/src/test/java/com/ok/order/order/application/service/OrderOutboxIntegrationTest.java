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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
    // 방금 남긴 줄도 재발송 대상으로 삼아, 경과 시간을 기다리지 않고 재발송을 확인한다
    private static final Duration NO_MIN_AGE = Duration.ZERO;

    @Autowired
    OrderOutboxRecordUseCase sut;

    @Autowired
    OrderOutboxRelayUseCase orderOutboxRelayUseCase;

    @Autowired
    OrderOutboxService orderOutboxService;

    @Autowired
    OrderOutboxJpaRepository orderOutboxRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    @Autowired
    @Qualifier(OrderOutboxRelayFacade.RELAY_EXECUTOR)
    ThreadPoolTaskExecutor relayExecutor;

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
        int sent = orderOutboxRelayUseCase.relayPending(100, NO_MIN_AGE);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sent).isGreaterThanOrEqualTo(2);
            softly.assertThat(reader.readValuesFor(key, READ_DURATION)).containsExactly("{\"seq\":1}", "{\"seq\":2}");
            softly.assertThat(orderOutboxRepository.findAll()).filteredOn(outbox -> key.equals(outbox.getMessageKey()))
                    .extracting(OrderOutbox::getStatus).containsOnly(OrderOutboxStatus.SENT);
        });
    }

    // 커밋 직후 발송이 대기열에서 밀린 사이 재발송이 같은 줄을 함께 보내면 받는 쪽이 중복을 받는다(#794)
    @Test
    void 재발송은_남긴_지_min_age가_지나지_않은_줄을_보내지_않는다() {
        // given: 커밋 직후 발송이 아직 보내는 중일 수 있는 줄
        orderOutboxRepository.save(OrderOutbox.pending(TOPIC, key, "{\"orderId\":14}"));

        // when
        int sent = orderOutboxRelayUseCase.relayPending(100, Duration.ofMinutes(1));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sent).isZero();
            softly.assertThat(statusOf(key)).isEqualTo(OrderOutboxStatus.PENDING);
            softly.assertThat(reader.readValuesFor(key, READ_DURATION)).isEmpty();
        });
    }

    @Test
    void 같은_줄을_두_번_보냄으로_바꾸면_뒤에_온_쪽은_중복을_알고_처음_보낸_시각이_남는다() {
        // given: 커밋 직후 발송과 재발송이 같은 줄을 함께 보낸 경우. 먼저 끝난 쪽이 보냄으로 바꿨다
        Long outboxId = orderOutboxRepository.save(OrderOutbox.pending(TOPIC, key, "{\"orderId\":15}")).getId();
        boolean first = orderOutboxService.markSent(outboxId);
        LocalDateTime firstSentAt = orderOutboxRepository.findById(outboxId).orElseThrow().getSentAt();

        // when
        boolean sut = orderOutboxService.markSent(outboxId);

        // then
        OrderOutbox outbox = orderOutboxRepository.findById(outboxId).orElseThrow();
        assertThat(outbox).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(first).isTrue();
            softly.assertThat(sut).isFalse();
            softly.assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.SENT);
            softly.assertThat(outbox.getSentAt()).isEqualTo(firstSentAt);
        });
    }

    @Test
    void 실패를_기록하려고_읽은_사이에_다른_발송이_보냄으로_바꿔도_실패_기록이_보냄을_되돌리지_않는다() throws Exception {
        // given: 커밋 직후 발송이 실패를 기록하려고 줄을 읽은 뒤, 재발송이 같은 줄을 보내고 보냄으로 커밋한 경우
        Long outboxId = orderOutboxRepository.save(OrderOutbox.pending(TOPIC, key, "{\"orderId\":16}")).getId();
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch sentCommitted = new CountDownLatch(1);
        CompletableFuture<Void> failureRecording = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> {
            OrderOutbox stale = orderOutboxRepository.findById(outboxId).orElseThrow();
            loaded.countDown();
            awaitQuietly(sentCommitted);
            stale.recordFailure("브로커 확인 대기 시간 초과");
        }));
        assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
        orderOutboxService.markSent(outboxId);
        sentCommitted.countDown();

        // when
        failureRecording.get(10, TimeUnit.SECONDS);

        // then: 읽은 값 전체를 덮어쓰면 status가 PENDING으로 돌아가 한 번 더 나간다
        OrderOutbox sut = orderOutboxRepository.findById(outboxId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(OrderOutboxStatus.SENT);
            softly.assertThat(sut.getSentAt()).isNotNull();
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
        });
    }

    // 커밋 직후 콜백은 DB 커넥션을 돌려주기 전이라, 대기열 자리를 기다리면 커넥션을 쥔 채 멈춘다(#715, 2차 부하테스트).
    // 전용 실행기의 스레드를 막고 대기열을 가득 채운 뒤 남겨, 기다리지 않고 끝나는지와 줄이 재발송으로 나가는지 본다
    @Test
    void 커밋_직후_발송_대기열이_가득_차도_기다리지_않고_끝나고_줄은_재발송이_보낸다() throws Exception {
        // given: 스레드를 모두 막고 대기열을 빈 작업으로 채운다
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = relayExecutor.getThreadPoolExecutor();
        try {
            for (int i = 0; i < relayExecutor.getMaxPoolSize(); i++) {
                pool.execute(() -> awaitQuietly(release));
            }
            while (pool.getQueue().remainingCapacity() > 0) {
                pool.execute(() -> { });
            }

            // when: 기다리면 이 시간 안에 끝나지 않는다
            CompletableFuture<Void> commit = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(
                    status -> sut.record(TOPIC, key, "{\"orderId\":13}")));
            commit.get(5, TimeUnit.SECONDS);

            // then: 바로 보내지 못하고 PENDING으로 남는다
            assertThat(statusOf(key)).isEqualTo(OrderOutboxStatus.PENDING);
        } finally {
            release.countDown();
        }

        // 막아 둔 작업이 끝난 뒤 재발송이 보낸다
        orderOutboxRelayUseCase.relayPending(100, NO_MIN_AGE);
        assertSoftly(softly -> {
            softly.assertThat(reader.readValuesFor(key, READ_DURATION)).containsExactly("{\"orderId\":13}");
            softly.assertThat(statusOf(key)).isEqualTo(OrderOutboxStatus.SENT);
        });
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
