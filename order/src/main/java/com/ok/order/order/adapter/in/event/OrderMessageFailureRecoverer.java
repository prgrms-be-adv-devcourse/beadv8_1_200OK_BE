package com.ok.order.order.adapter.in.event;

import com.ok.order.order.application.dto.OrderMessageFailureCommand;
import com.ok.order.order.application.port.in.OrderMessageFailureUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.serializer.DeserializationException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * 다시 시도해도 처리하지 못한 메시지를 주문 실패 기록으로 넘긴다 (#666, 지갑과 같은 방식).
 * 기록하지 못하면 예외를 그대로 던진다. 오류 처리기는 이 메시지의 오프셋을 옮기지 않고 다시 받으므로, DB가 살아나면 다시 처리되거나 기록된다.
 */
class OrderMessageFailureRecoverer implements ConsumerRecordRecoverer {

    // 컨슈머 그룹을 알 수 없을 때 남기는 값. 리스너 스레드 밖에서 불릴 때만 생긴다
    static final String UNKNOWN_GROUP = "unknown";

    private final OrderMessageFailureUseCase orderMessageFailureUseCase;
    private final JsonMapper jsonMapper;

    OrderMessageFailureRecoverer(OrderMessageFailureUseCase orderMessageFailureUseCase, JsonMapper jsonMapper) {
        this.orderMessageFailureUseCase = orderMessageFailureUseCase;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
        orderMessageFailureUseCase.record(new OrderMessageFailureCommand(
                consumerGroup(),
                record.topic(),
                record.partition(),
                record.offset(),
                record.key() == null ? null : String.valueOf(record.key()),
                payloadOf(record, exception),
                failureReasonOf(cause)));
    }

    // 주문 안에서도 리스너마다 그룹이 달라(#648) 어느 리스너가 실패했는지 남긴다.
    // 컨테이너가 리스너 스레드에 그룹을 넣어 두고, 오류 처리기는 같은 스레드에서 이 메서드를 부른다
    private static String consumerGroup() {
        String group = KafkaUtils.getConsumerGroupId();
        return group == null || group.isBlank() ? UNKNOWN_GROUP : group;
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
        // 읽은 DTO를 다시 JSON으로 쓴다. 받은 바이트와 띄어쓰기·필드 순서는 다를 수 있고, DTO에 없는 필드는 읽을 때 버려져 빠진다.
        // 주문이 쓰는 필드 값은 같아 다시 처리하는 데 충분하다
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            // 다시 JSON으로 쓰는 실패는 기다려도 풀리지 않는다. 여기서 예외를 내면 같은 메시지를 계속 다시 받아 파티션이 멈추므로 값의 문자열로 남긴다
            return String.valueOf(value);
        }
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
