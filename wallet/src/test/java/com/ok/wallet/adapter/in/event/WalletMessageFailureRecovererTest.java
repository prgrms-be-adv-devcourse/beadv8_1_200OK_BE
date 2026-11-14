package com.ok.wallet.adapter.in.event;

import com.ok.wallet.application.dto.MessageFailureCommand;
import com.ok.wallet.application.port.in.MessageFailureUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

class WalletMessageFailureRecovererTest {
    private static final String TOPIC = "payout.seller-payout-created";

    private final MessageFailureUseCase messageFailureUseCase = mock(MessageFailureUseCase.class);
    private final WalletMessageFailureRecoverer recoverer =
            new WalletMessageFailureRecoverer(messageFailureUseCase, JsonMapper.builder().build());

    record SampleMessage(Long payoutId, Long amount) {
    }

    @Test
    void 읽은_메시지는_필드_값을_JSON으로_남기고_가장_안쪽_원인을_실패_이유로_남긴다() {
        // given
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(TOPIC, 2, 40L, "12", new SampleMessage(12L, 0L));
        Exception exception = new ListenerExecutionFailedException("리스너 실패",
                new IllegalArgumentException("amount가 0 이하"));

        // when
        recoverer.accept(record, exception);

        // then
        MessageFailureCommand sut = recordedCommand();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.topic()).isEqualTo(TOPIC);
            softly.assertThat(sut.partition()).isEqualTo(2);
            softly.assertThat(sut.offset()).isEqualTo(40L);
            softly.assertThat(sut.key()).isEqualTo("12");
            softly.assertThat(sut.payload()).isEqualTo("{\"payoutId\":12,\"amount\":0}");
            softly.assertThat(sut.failureReason()).isEqualTo("IllegalArgumentException: amount가 0 이하");
        });
    }

    @Test
    void JSON으로_읽지_못한_메시지는_받은_그대로를_남긴다() {
        // given: 값은 비어 오고 받은 바이트는 예외에 담겨 온다
        byte[] data = "이건 JSON이 아니다".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(TOPIC, 0, 3L, "12", null);
        Exception exception = new ListenerExecutionFailedException("리스너 실패",
                new DeserializationException("JSON을 읽을 수 없음", data, false, new RuntimeException("Unexpected character")));

        // when
        recoverer.accept(record, exception);

        // then
        assertThat(recordedCommand().payload()).isEqualTo("이건 JSON이 아니다");
    }

    @Test
    void 키와_값이_없어도_남긴다() {
        // given
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(TOPIC, 0, 3L, null, null);
        Exception exception = new ListenerExecutionFailedException("리스너 실패",
                new DataAccessResourceFailureException("DB 끊김"));

        // when
        recoverer.accept(record, exception);

        // then
        MessageFailureCommand sut = recordedCommand();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.key()).isNull();
            softly.assertThat(sut.payload()).isNull();
            softly.assertThat(sut.failureReason()).isEqualTo("DataAccessResourceFailureException: DB 끊김");
        });
    }

    private MessageFailureCommand recordedCommand() {
        ArgumentCaptor<MessageFailureCommand> captor = ArgumentCaptor.forClass(MessageFailureCommand.class);
        then(messageFailureUseCase).should().record(captor.capture());
        return captor.getValue();
    }
}
