package com.ok.refund.adapter.in.event;

import com.ok.refund.adapter.in.event.dto.WalletRefundCompletedMessage;
import com.ok.refund.adapter.in.event.dto.WalletRefundFailedMessage;
import com.ok.refund.application.port.in.HandleWalletRefundResultUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 지갑이 보내는 예치금 반환 결과(완료·실패)를 Kafka로 받아 입력 포트에 넘기는 입구다.
 * 여기서는 값이 형식에 맞는지만 보고, 환불 상태·금액 대조 같은 업무 판단은 서비스가 한다.
 */
@Component
@RequiredArgsConstructor
public class RefundWalletKafkaListener {

    // 지갑 단계 줄의 이유(refund_step.last_reason) 컬럼이 VARCHAR(100)이다. 도메인 RefundStep이 100자로 잘라 저장하므로 DB 오류는 나지 않지만,
    // 넘는 값을 조용히 잘라 받으면 보내는 쪽의 잘못된 값이 드러나지 않고 이유의 뒷부분이 사라져 입구에서 거절한다
    // (서비스의 RefundStepRunner.MAX_FAILURE_CODE_LENGTH와 같은 값. 입력 어댑터는 서비스를 알 수 없어 따로 둔다)
    static final int MAX_REASON_LENGTH = 100;

    private final HandleWalletRefundResultUseCase handleWalletRefundResultUseCase;

    // 메시지에 보내는 쪽 클래스 이름(타입 헤더)이 없어서 리스너마다 읽을 타입을 정한다
    // 값이 틀린 메시지는 IllegalArgumentException을 던진다. 환불 오류 처리기(RefundKafkaConsumerConfig)는 이 예외를
    // 다시 시도하지 않고 ERROR 로그를 남긴 뒤 다음 메시지로 넘어간다(다시 받아도 같은 값이라 결과가 같다)
    @KafkaListener(
            topics = RefundInboundTopics.WALLET_REFUND_COMPLETED,
            groupId = RefundInboundTopics.CONSUMER_GROUP,
            containerFactory = RefundKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.refund.adapter.in.event.dto.WalletRefundCompletedMessage")
    void onWalletRefundCompleted(WalletRefundCompletedMessage message) {
        requireIds(message.refundId(), message.orderId(), message.buyerId(), message.amount(), message);
        // DB·상품 HTTP 오류 같은 일시적 실패는 예외를 그대로 전파해 오류 처리기가 간격을 늘려 다시 시도하게 한다
        handleWalletRefundResultUseCase.completeAfterWalletRefund(message.toCommand());
    }

    @KafkaListener(
            topics = RefundInboundTopics.WALLET_REFUND_FAILED,
            groupId = RefundInboundTopics.CONSUMER_GROUP,
            containerFactory = RefundKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.refund.adapter.in.event.dto.WalletRefundFailedMessage")
    void onWalletRefundFailed(WalletRefundFailedMessage message) {
        requireIds(message.refundId(), message.orderId(), message.buyerId(), message.amount(), message);
        String reason = message.reason();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("[REFUND_WALLET_RESULT_INVALID] 실패 이유가 비어 있습니다. message=" + message);
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("[REFUND_WALLET_RESULT_INVALID] 실패 이유가 " + MAX_REASON_LENGTH
                    + "자를 넘습니다. length=" + reason.length() + ", refundId=" + message.refundId());
        }
        handleWalletRefundResultUseCase.recordWalletRefundFailure(message.toCommand());
    }

    private void requireIds(Long refundId, Long orderId, Long buyerId, Long amount, Object message) {
        if (refundId == null || orderId == null || buyerId == null) {
            throw new IllegalArgumentException("[REFUND_WALLET_RESULT_INVALID] refundId, orderId, buyerId는 비어 있을 수 없습니다. message=" + message);
        }
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("[REFUND_WALLET_RESULT_INVALID] amount는 1 이상이어야 합니다. message=" + message);
        }
    }
}
