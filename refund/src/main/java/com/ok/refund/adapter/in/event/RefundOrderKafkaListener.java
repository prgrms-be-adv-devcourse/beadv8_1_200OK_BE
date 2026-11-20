package com.ok.refund.adapter.in.event;

import com.ok.refund.adapter.in.event.dto.OrderRefundRevertFailedMessage;
import com.ok.refund.adapter.in.event.dto.OrderRefundRevertedMessage;
import com.ok.refund.application.port.in.RefundSagaUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 주문이 보내는 취소 기록 되돌리기 결과(되돌림·실패, #622)를 Kafka로 받아 입력 포트에 넘기는 입구다.
 * 여기서는 값이 형식에 맞는지만 보고, 환불 상태·주문 상품 대조 같은 업무 판단은 승인 뒤 단계 지휘자가 한다(#575).
 * 지갑 결과 리스너와 따로 둔 이유: 보내는 모듈과 메시지 필드가 달라 값 검사가 다르다. 오류 처리 규칙(값이 틀리면 다시 시도하지 않음)은 같다.
 */
@Component
@RequiredArgsConstructor
public class RefundOrderKafkaListener {

    // 되돌리기 줄의 이유(refund_step.last_reason)도 VARCHAR(100)이라 지갑 결과 리스너와 같은 한도로 입구에서 거절한다
    static final int MAX_REASON_LENGTH = RefundWalletKafkaListener.MAX_REASON_LENGTH;

    private final RefundSagaUseCase refundSagaUseCase;

    // 값이 틀린 메시지는 IllegalArgumentException을 던진다. 환불 오류 처리기(RefundKafkaConsumerConfig)는 이 예외를
    // 다시 시도하지 않고 ERROR 로그를 남긴 뒤 다음 메시지로 넘어간다(다시 받아도 같은 값이라 결과가 같다).
    // DB 오류 같은 일시적 실패는 예외를 그대로 전파해 오류 처리기가 간격을 늘려 다시 시도하게 한다
    @KafkaListener(
            topics = RefundInboundTopics.ORDER_REFUND_REVERTED,
            groupId = RefundInboundTopics.ORDER_REVERT_COMPLETED_GROUP,
            containerFactory = RefundKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.refund.adapter.in.event.dto.OrderRefundRevertedMessage")
    void onOrderRefundReverted(OrderRefundRevertedMessage message) {
        requireIds(message.refundId(), message.orderId(), message.orderItemId(), message);
        refundSagaUseCase.recordOrderRefundReverted(message.toCommand());
    }

    @KafkaListener(
            topics = RefundInboundTopics.ORDER_REFUND_REVERT_FAILED,
            groupId = RefundInboundTopics.ORDER_REVERT_FAILED_GROUP,
            containerFactory = RefundKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.refund.adapter.in.event.dto.OrderRefundRevertFailedMessage")
    void onOrderRefundRevertFailed(OrderRefundRevertFailedMessage message) {
        requireIds(message.refundId(), message.orderId(), message.orderItemId(), message);
        if (message.reason() == null || message.reason().isBlank()) {
            throw new IllegalArgumentException("[REFUND_ORDER_RESULT_INVALID] 실패 이유가 비어 있습니다. message=" + message);
        }
        if (message.reason().length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("[REFUND_ORDER_RESULT_INVALID] 실패 이유가 " + MAX_REASON_LENGTH
                    + "자를 넘습니다. length=" + message.reason().length() + ", refundId=" + message.refundId());
        }
        refundSagaUseCase.recordOrderRefundRevertFailure(message.toCommand());
    }

    private void requireIds(Long refundId, Long orderId, Long orderItemId, Object message) {
        if (refundId == null || orderId == null || orderItemId == null) {
            throw new IllegalArgumentException("[REFUND_ORDER_RESULT_INVALID] refundId, orderId, orderItemId는 비어 있을 수 없습니다. message=" + message);
        }
    }
}
