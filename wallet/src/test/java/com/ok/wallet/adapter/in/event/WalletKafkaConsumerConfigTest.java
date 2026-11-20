package com.ok.wallet.adapter.in.event;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

// 단위 테스트: 지갑 Kafka 오류 처리 규칙. 실제 Kafka 없이 오류 처리기에 실패한 메시지를 직접 넘겨 확인한다
@ExtendWith(OutputCaptureExtension.class)
class WalletKafkaConsumerConfigTest {
    private static final String TOPIC = "payout.seller-payout-created";
    private static final ConsumerRecord<String, Object> RECORD = new ConsumerRecord<>(TOPIC, 0, 7L, "12", "{}");
    // 다시 시도 간격을 기다리지 않게, 다시 시도 없이 바로 기록으로 넘기는 간격
    private static final FixedBackOff NO_RETRY = new FixedBackOff(0L, 0L);
    // 어느 간격을 골랐는지 몇 번째 실패에서 기록되는지로 가린다. 기본 간격이 "다시 시도 없음"이면 오류 처리기가
    // 간격을 고르지 않고 바로 기록하므로, 기본 간격은 1번, 일시적 보내기 실패 간격은 2번 다시 시도로 둔다
    private static final FixedBackOff ONE_RETRY = new FixedBackOff(0L, 1L);
    private static final FixedBackOff TWO_RETRIES = new FixedBackOff(0L, 2L);
    private static final long THIRTY_MINUTES_MILLIS = Duration.ofMinutes(30).toMillis();

    @Test
    void 값이_틀려_던진_예외는_다시_시도하지_않는다() {
        // given
        DefaultErrorHandler sut = WalletKafkaConsumerConfig.walletErrorHandler((record, e) -> { }, NO_RETRY, NO_RETRY);

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

    @ParameterizedTest
    @MethodSource("잠깐_보내지_못한_실패")
    void 결과_보내기의_일시적_실패는_감싼_원인을_따라가_가려낸다(Exception exception) {
        // when
        boolean sut = WalletKafkaConsumerConfig.isTransientSendFailure(exception);

        // then
        assertThat(sut).isTrue();
    }

    static Stream<Exception> 잠깐_보내지_못한_실패() {
        return Stream.of(
                // 지갑 보내기가 브로커 거절을 감싸 올린 모양
                new IllegalStateException("환불 반환 결과를 보내지 못했습니다. refundId=7",
                        new NotEnoughReplicasException("복제본 부족")),
                // 저장 확인을 기다리다 시간이 다 된 모양
                new IllegalStateException("환불 반환 결과를 보내지 못했습니다. refundId=7", new TimeoutException()),
                // 리스너 포장이 한 겹 더 있어도 가려낸다
                failure(new IllegalStateException("주문 결제 결과를 보내지 못했습니다. orderId=10",
                        new NetworkException("브로커 연결 끊김"))));
    }

    @ParameterizedTest
    @MethodSource("일시적_보내기_실패가_아닌_실패")
    void 다시_해도_안_되는_보내기_실패와_다른_오류는_일시적_실패로_보지_않는다(Exception exception) {
        // when
        boolean sut = WalletKafkaConsumerConfig.isTransientSendFailure(exception);

        // then
        assertThat(sut).isFalse();
    }

    static Stream<Exception> 일시적_보내기_실패가_아닌_실패() {
        return Stream.of(
                // 메시지 크기 초과는 다시 보내도 같다. 30분 붙잡으면 같은 파티션의 뒤 메시지가 모두 멈춘다
                new IllegalStateException("환불 반환 결과를 보내지 못했습니다. refundId=7",
                        new RecordTooLargeException("메시지 크기 초과")),
                failure(new IllegalStateException("DB 끊김")),
                failure(new IllegalArgumentException("금액이 0 이하입니다")));
    }

    @Test
    void 일시적_보내기_실패는_1초부터_두_배씩_최대_1분_간격으로_30분까지_다시_시도한다() {
        // given
        BackOffExecution sut = WalletKafkaConsumerConfig.transientSendFailureBackOff().start();

        // when: 다음 대기 시간을 끝(STOP)이 나올 때까지 모은다
        List<Long> intervals = new ArrayList<>();
        for (long next = sut.nextBackOff(); next != BackOffExecution.STOP; next = sut.nextBackOff()) {
            intervals.add(next);
        }

        // then: 1·2·4·8·16·32초 뒤 1분씩. 간격의 합이 30분을 채우면 더 시도하지 않고 기록으로 넘긴다
        long total = intervals.stream().mapToLong(Long::longValue).sum();
        assertSoftly(softly -> {
            softly.assertThat(intervals).startsWith(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L);
            softly.assertThat(intervals).allMatch(interval -> interval <= 60_000L);
            softly.assertThat(total - intervals.getLast()).isLessThan(THIRTY_MINUTES_MILLIS);
            softly.assertThat(total).isGreaterThanOrEqualTo(THIRTY_MINUTES_MILLIS);
        });
    }

    @Test
    void 가장_긴_간격과_보내기_대기를_합쳐도_소비자가_그룹에서_빠지는_기준보다_짧다() {
        // given: 오류 처리기는 소비자 스레드에서 간격만큼 잠들고, 다시 받은 메시지는 결과를 보내며 기다린다.
        // 보내기는 토픽 정보를 모르면 저장 확인을 기다리기 전에 max.block.ms까지 멈춘다(앱을 띄운 뒤 그 토픽에
        // 처음 보낼 때 브로커가 없으면, 로컬에서 60초 멈춤을 확인). 그 뒤 저장 확인을 최대 10초 기다린다.
        // 10초는 WalletKafkaSender.SEND_TIMEOUT 값이다. 받는 쪽 패키지에서는 보내는 쪽 상수를 쓸 수 없어 값을 적는다
        Duration sendTimeout = Duration.ofSeconds(10);
        // 지갑은 max.block.ms와 max.poll.interval.ms를 바꾸지 않아(common-kafka.yaml) Kafka 기본값(1분, 5분)을 쓴다
        Duration maxBlock = Duration.ofMillis(((Number) ProducerConfig.configDef().defaultValues()
                .get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).longValue());
        Duration maxPollInterval = Duration.ofMillis(((Number) ConsumerConfig.configDef().defaultValues()
                .get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG)).longValue());

        // when: 가장 긴 간격 + 토픽 정보 대기 + 저장 확인 대기. 한 메시지는 토픽 하나에 보낸다
        Duration sut = Duration.ofMillis(WalletKafkaConsumerConfig.TRANSIENT_SEND_MAX_INTERVAL_MILLIS)
                .plus(maxBlock)
                .plus(sendTimeout);

        // then: 넘으면 소비자가 그룹에서 빠졌다 다시 들어와 같은 메시지를 다시 처리한다
        assertThat(sut).isLessThan(maxPollInterval);
    }

    @Test
    void 일시적_보내기_실패는_일시적_실패_간격을_골라_기본_간격보다_오래_다시_시도한다() {
        // given
        List<ConsumerRecord<?, ?>> recovered = new ArrayList<>();
        DefaultErrorHandler sut = WalletKafkaConsumerConfig.walletErrorHandler(
                (record, e) -> recovered.add(record), ONE_RETRY, TWO_RETRIES);
        Consumer<?, ?> consumer = mock(Consumer.class);
        ListenerExecutionFailedException exception = failure(sendFailure());

        // when: 같은 메시지가 두 번 실패한다. 기본 간격(1번 다시 시도)이었다면 두 번째에 기록된다
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> sut.handleRemaining(exception, List.of(RECORD), consumer, container()))
                    .isInstanceOf(RuntimeException.class);
        }

        // then: 기록하지 않고 매번 같은 위치로 되돌려 다시 받는다
        assertThat(recovered).isEmpty();
        then(consumer).should(times(2)).seek(new TopicPartition(TOPIC, 0), 7L);
    }

    @Test
    void 보내기와_상관없는_오류는_기본_간격대로_다시_시도하고_경고를_남기지_않는다(CapturedOutput output) {
        // given
        List<ConsumerRecord<?, ?>> recovered = new ArrayList<>();
        DefaultErrorHandler sut = WalletKafkaConsumerConfig.walletErrorHandler(
                (record, e) -> recovered.add(record), ONE_RETRY, TWO_RETRIES);
        Consumer<?, ?> consumer = mock(Consumer.class);
        ListenerExecutionFailedException exception = failure(new IllegalStateException("DB 끊김"));

        // when: 같은 메시지가 두 번 실패한다
        assertThatThrownBy(() -> sut.handleRemaining(exception, List.of(RECORD), consumer, container()))
                .isInstanceOf(RuntimeException.class);
        sut.handleRemaining(exception, List.of(RECORD), consumer, container());

        // then: 기본 간격(1번 다시 시도)대로 두 번째에 기록하고, 보내기 실패 경고는 남기지 않는다
        assertThat(recovered).containsExactly(RECORD);
        assertThat(output.getOut()).doesNotContain("[WALLET_SEND_RETRY]");
    }

    @Test
    void 일시적_보내기_실패를_다시_시도할_때마다_경고_로그를_남긴다(CapturedOutput output) {
        // given
        DefaultErrorHandler sut = WalletKafkaConsumerConfig.walletErrorHandler((record, e) -> { }, ONE_RETRY, ONE_RETRY);
        Consumer<?, ?> consumer = mock(Consumer.class);
        ListenerExecutionFailedException exception = failure(sendFailure());

        // when: 같은 메시지가 두 번 실패한다. 두 번째는 다시 시도 횟수를 다 써서 기록으로 넘어간다
        assertThatThrownBy(() -> sut.handleRemaining(exception, List.of(RECORD), consumer, container()))
                .isInstanceOf(RuntimeException.class);
        sut.handleRemaining(exception, List.of(RECORD), consumer, container());

        // then
        assertThat(output.getOut()).contains(
                "[WALLET_SEND_RETRY]", "topic=" + TOPIC, "key=12", "attempt=1", "attempt=2",
                "NotEnoughReplicasException: 복제본 부족");
    }

    @Test
    void 기록하면_오프셋을_다음_메시지로_옮긴다() {
        // given
        List<ConsumerRecord<?, ?>> recovered = new ArrayList<>();
        DefaultErrorHandler sut = WalletKafkaConsumerConfig.walletErrorHandler((record, e) -> recovered.add(record), NO_RETRY, NO_RETRY);
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
        DefaultErrorHandler sut = WalletKafkaConsumerConfig.walletErrorHandler(failingRecoverer, NO_RETRY, NO_RETRY);
        Consumer<?, ?> consumer = mock(Consumer.class);

        // when & then: 오류 처리기가 같은 위치로 되돌리고 예외로 알린다
        assertThatThrownBy(() -> sut.handleRemaining(
                failure(new IllegalStateException("DB 끊김")), List.of(RECORD), consumer, container()))
                .isInstanceOf(RuntimeException.class);
        then(consumer).should().seek(new TopicPartition(TOPIC, 0), 7L);
    }

    // 지갑 보내기가 브로커 거절(복제본 부족)을 감싸 올린 모양
    private static IllegalStateException sendFailure() {
        return new IllegalStateException("환불 반환 결과를 보내지 못했습니다. refundId=7",
                new NotEnoughReplicasException("복제본 부족"));
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
