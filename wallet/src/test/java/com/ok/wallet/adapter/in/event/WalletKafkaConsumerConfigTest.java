package com.ok.wallet.adapter.in.event;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

// 단위 테스트: 지갑 Kafka 오류 처리 규칙. 실제 Kafka 없이 오류 처리기에 실패한 메시지를 직접 넘겨 확인한다
class WalletKafkaConsumerConfigTest {
    private static final String TOPIC = "payout.seller-payout-created";
    private static final ConsumerRecord<String, Object> RECORD = new ConsumerRecord<>(TOPIC, 0, 7L, "12", "{}");
    // 다시 시도 간격을 기다리지 않게, 다시 시도 없이 바로 기록으로 넘기는 간격
    private static final FixedBackOff NO_RETRY = new FixedBackOff(0L, 0L);

    @Test
    void 값이_틀려_던진_예외는_다시_시도하지_않는다() {
        // given
        DefaultErrorHandler sut = WalletKafkaConsumerConfig.walletErrorHandler((record, e) -> { }, NO_RETRY);

        // when: removeClassification은 등록돼 있던 분류를 돌려준다 (false = 다시 시도하지 않음, null = 등록 안 됨)
        Boolean retryable = sut.removeClassification(IllegalArgumentException.class);

        // then
        assertThat(retryable).isFalse();
    }

    @Test
    void 일시적_실패는_1초부터_두_배씩_3번_다시_시도한다() {
        // given
        BackOffExecution sut = WalletKafkaConsumerConfig.walletBackOff().start();

        // when: 다음 대기 시간을 끝(STOP)이 나올 때까지 모은다
        List<Long> intervals = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            intervals.add(sut.nextBackOff());
        }

        // then: 1·2·4초(합 7초) 뒤 더 시도하지 않고 기록으로 넘긴다
        assertThat(intervals).containsExactly(1_000L, 2_000L, 4_000L, BackOffExecution.STOP);
    }

    @Test
    void 기록하면_오프셋을_다음_메시지로_옮긴다() {
        // given
        List<ConsumerRecord<?, ?>> recovered = new ArrayList<>();
        DefaultErrorHandler sut = WalletKafkaConsumerConfig.walletErrorHandler((record, e) -> recovered.add(record), NO_RETRY);
        Consumer<?, ?> consumer = mock(Consumer.class);

        // when: 오류 처리기가 예외 없이 끝나면 컨테이너는 이 메시지를 처리한 것으로 보고 오프셋을 옮긴다
        sut.handleRemaining(failure(new IllegalStateException("DB 끊김")), List.of(RECORD), consumer, container());

        // then: 기록됐으므로 같은 위치로 되돌리지 않는다
        assertThat(recovered).containsExactly(RECORD);
        then(consumer).should(never()).seek(any(TopicPartition.class), any(Long.class));
    }

    @Test
    void 기록마저_실패하면_오프셋을_옮기지_않고_같은_메시지를_다시_받는다() {
        // given: 실패 기록 저장이 DB 오류로 실패한다
        ConsumerRecordRecoverer failingRecoverer = (record, e) -> {
            throw new IllegalStateException("실패 기록 저장 실패");
        };
        DefaultErrorHandler sut = WalletKafkaConsumerConfig.walletErrorHandler(failingRecoverer, NO_RETRY);
        Consumer<?, ?> consumer = mock(Consumer.class);

        // when & then: 오류 처리기가 같은 위치로 되돌리고 예외로 알린다
        assertThatThrownBy(() -> sut.handleRemaining(
                failure(new IllegalStateException("DB 끊김")), List.of(RECORD), consumer, container()))
                .isInstanceOf(RuntimeException.class);
        then(consumer).should().seek(new TopicPartition(TOPIC, 0), 7L);
    }

    private static ListenerExecutionFailedException failure(Exception cause) {
        return new ListenerExecutionFailedException("리스너 실패", cause);
    }

    private static MessageListenerContainer container() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        given(container.getContainerProperties()).willReturn(new ContainerProperties(TOPIC));
        given(container.isRunning()).willReturn(true);
        return container;
    }
}
