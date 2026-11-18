package com.ok.order.order.adapter.in.event;

import com.ok.order.order.adapter.in.event.dto.RefundOrderRevertRequestedMessage;
import com.ok.order.order.application.dto.RevertOrderItemRefundCommand;
import com.ok.order.order.application.port.in.RevertOrderItemRefundUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 환불이 Kafka로 보내는 취소 기록 되돌리기 요청을 받는다(#604).
 * 환불은 결과가 오지 않으면 같은 요청을 다시 보낸다. 이미 되돌린 요청이면 다시 되돌림으로 답하므로 여러 번 받아도 결과가 같다.
 * 처리 중 예외는 주문 오류 처리기({@link OrderKafkaConsumerConfig})가 다시 시도한 뒤 실패 기록 표에 남긴다.
 */
@Component
@RequiredArgsConstructor
class RefundRevertKafkaListener {

    private final RevertOrderItemRefundUseCase revertOrderItemRefund;

    // 메시지에 보내는 쪽 클래스 이름(타입 헤더)이 없어서 읽을 타입을 리스너가 정한다
    @KafkaListener(
            topics = RefundTopics.REFUND_ORDER_REVERT_REQUESTED,
            groupId = RefundTopics.REFUND_REVERT_GROUP,
            containerFactory = OrderKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.order.order.adapter.in.event.dto.RefundOrderRevertRequestedMessage")
    void onRevertRequested(RefundOrderRevertRequestedMessage message) {
        revertOrderItemRefund.revertRefund(toCommand(message));
    }

    // 값이 빠졌거나 틀리면 결과를 보낼 대상도 알 수 없어 답하지 않는다.
    // IllegalArgumentException은 오류 처리기가 다시 시도하지 않고 바로 실패 기록에 남긴다
    private RevertOrderItemRefundCommand toCommand(RefundOrderRevertRequestedMessage message) {
        if (message.refundId() == null || message.orderId() == null || message.orderItemId() == null) {
            throw new IllegalArgumentException("되돌리기 요청에 환불·주문·주문 상품 ID가 없습니다. refundId=" + message.refundId()
                    + ", orderId=" + message.orderId() + ", orderItemId=" + message.orderItemId());
        }
        if (message.quantity() == null || message.quantity() < 1) {
            throw new IllegalArgumentException("되돌리기 요청의 수량은 1 이상이어야 합니다. refundId=" + message.refundId()
                    + ", quantity=" + message.quantity());
        }
        return new RevertOrderItemRefundCommand(message.refundId(), message.orderId(), message.orderItemId(), message.quantity());
    }
}
