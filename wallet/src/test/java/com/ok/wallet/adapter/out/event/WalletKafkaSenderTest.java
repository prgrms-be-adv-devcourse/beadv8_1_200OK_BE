package com.ok.wallet.adapter.out.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

// 여러 토픽에 함께 보내는 길을 본다. 한 토픽 보내기의 실패 경로(거절·시간 초과·중단·즉시 오류)는 결과 출구 테스트들이 본다.
// 실제 브로커로는 한쪽만 실패하거나 늦게 답하는 상황을 만들기 어려워 KafkaTemplate을 Mockito로 대신한다
@ExtendWith(MockitoExtension.class)
class WalletKafkaSenderTest {

    private static final String NEW_TOPIC = "wallet.test-result.v1";
    private static final String OLD_TOPIC = "wallet.test-result";
    private static final String KEY = "10";
    private static final String MESSAGE = "결과";
    private static final String FAILED_MESSAGE = "결과를 보내지 못했습니다. requestId=7";

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    @BeforeEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void 모든_토픽에_같은_키와_값을_보낸다() {
        // given
        WalletKafkaSender sut = new WalletKafkaSender(kafkaTemplate, Duration.ofSeconds(1));
        given(kafkaTemplate.send(NEW_TOPIC, KEY, MESSAGE)).willReturn(stored());
        given(kafkaTemplate.send(OLD_TOPIC, KEY, MESSAGE)).willReturn(stored());

        // when
        sut.send(List.of(NEW_TOPIC, OLD_TOPIC), KEY, MESSAGE, FAILED_MESSAGE);

        // then
        then(kafkaTemplate).should().send(NEW_TOPIC, KEY, MESSAGE);
        then(kafkaTemplate).should().send(OLD_TOPIC, KEY, MESSAGE);
    }

    @Test
    void 한쪽_토픽만_실패해도_실패한_토픽과_원인을_담은_예외를_던진다() {
        // given: 새 이름은 저장됐고 옛 이름만 거절. 다시 받아 보낼 때 두 토픽에 모두 다시 나가고, 받는 쪽이 중복을 거른다
        WalletKafkaSender sut = new WalletKafkaSender(kafkaTemplate, Duration.ofSeconds(1));
        KafkaException rejected = new KafkaException("브로커 거절");
        given(kafkaTemplate.send(NEW_TOPIC, KEY, MESSAGE)).willReturn(stored());
        given(kafkaTemplate.send(OLD_TOPIC, KEY, MESSAGE)).willReturn(CompletableFuture.failedFuture(rejected));

        // when & then
        assertThatThrownBy(() -> sut.send(List.of(NEW_TOPIC, OLD_TOPIC), KEY, MESSAGE, FAILED_MESSAGE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(FAILED_MESSAGE + ", topic=" + OLD_TOPIC)
                .hasCause(rejected);
    }

    @Test
    void 앞_토픽을_기다린_시간만큼_뒤_토픽은_덜_기다린다() {
        // given: 앞 토픽은 0.3초 뒤에 저장되고 뒤 토픽은 답이 없다. 토픽마다 0.5초씩 따로 기다리면 0.8초가 걸린다
        WalletKafkaSender sut = new WalletKafkaSender(kafkaTemplate, Duration.ofMillis(500));
        given(kafkaTemplate.send(NEW_TOPIC, KEY, MESSAGE)).willReturn(storedAfter(300));
        given(kafkaTemplate.send(OLD_TOPIC, KEY, MESSAGE)).willReturn(new CompletableFuture<>());
        long startedAt = System.nanoTime();

        // when & then
        assertThatThrownBy(() -> sut.send(List.of(NEW_TOPIC, OLD_TOPIC), KEY, MESSAGE, FAILED_MESSAGE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(FAILED_MESSAGE + ", topic=" + OLD_TOPIC)
                .hasCauseInstanceOf(TimeoutException.class);
        // 합쳐서 0.5초까지만 기다린다. 0.8초보다 넉넉히 짧은 0.7초로 잡아 느린 실행 환경에서도 흔들리지 않게 한다
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(700));
    }

    private static CompletableFuture<SendResult<String, Object>> stored() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, Object>> storedAfter(long millis) {
        return CompletableFuture.supplyAsync(() -> null,
                CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
    }
}
