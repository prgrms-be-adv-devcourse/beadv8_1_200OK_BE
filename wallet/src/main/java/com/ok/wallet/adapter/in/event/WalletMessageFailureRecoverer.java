package com.ok.wallet.adapter.in.event;

import com.ok.wallet.application.dto.MessageFailureCommand;
import com.ok.wallet.application.port.in.MessageFailureUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.support.serializer.DeserializationException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * 다시 시도해도 처리하지 못한 메시지를 지갑 실패 기록으로 넘긴다.
 * 기록하지 못하면 예외를 그대로 던진다. 오류 처리기는 이 메시지의 오프셋을 옮기지 않고 다시 받으므로, DB가 살아나면 다시 처리되거나 기록된다.
 */
class WalletMessageFailureRecoverer implements ConsumerRecordRecoverer {

    private final MessageFailureUseCase messageFailureUseCase;
    private final JsonMapper jsonMapper;

    WalletMessageFailureRecoverer(MessageFailureUseCase messageFailureUseCase, JsonMapper jsonMapper) {
        this.messageFailureUseCase = messageFailureUseCase;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
        messageFailureUseCase.record(new MessageFailureCommand(
                record.topic(),
                record.partition(),
                record.offset(),
                record.key() == null ? null : String.valueOf(record.key()),
                payloadOf(record, exception),
                failureReasonOf(cause)));
    }

    // 메시지가 없는 예외를 "NullPointerException: null"로 남기면 값이 null이라 실패했다는 뜻으로 잘못 읽히므로 예외 이름만 남긴다
    private static String failureReasonOf(Throwable cause) {
        String name = cause.getClass().getSimpleName();
        return cause.getMessage() == null ? name : name + ": " + cause.getMessage();
    }

    private String payloadOf(ConsumerRecord<?, ?> record, Exception exception) {
        // JSON으로 읽지 못한 메시지는 값이 비어 오고, 받은 그대로의 바이트가 예외에 담겨 온다
        DeserializationException deserializationException = findDeserializationException(exception);
        if (deserializationException != null) {
            byte[] data = deserializationException.getData();
            return data == null ? null : new String(data, StandardCharsets.UTF_8);
        }
        Object value = record.value();
        if (value == null) {
            return null;
        }
        // 읽은 DTO를 다시 JSON으로 쓴다. 받은 바이트와 띄어쓰기·필드 순서는 다를 수 있지만 필드 값은 같아 다시 처리하는 데 충분하다
        return jsonMapper.writeValueAsString(value);
    }

    private static DeserializationException findDeserializationException(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof DeserializationException deserializationException) {
                return deserializationException;
            }
            current = current.getCause();
        }
        return null;
    }
}
