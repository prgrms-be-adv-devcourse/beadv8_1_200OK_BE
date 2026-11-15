package com.ok.wallet.adapter.in.event;

import com.ok.wallet.adapter.in.event.dto.RefundApprovedMessage;
import com.ok.wallet.application.dto.OrderRefundCommand;
import com.ok.wallet.application.port.in.OrderRefundUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 환불이 Kafka로 보내는 예치금 반환 요청을 받아 구매자 지갑에 돌려주고 결과를 환불에 보낸다.
 * 같은 환불이 여러 번 와도 서비스가 refundId로 한 번만 돌려주고, 올 때마다 처음 결과를 다시 보낸다.
 */
@Component
@RequiredArgsConstructor
public class RefundKafkaListener {
    private final OrderRefundUseCase orderRefundUseCase;

    // 메시지에 보내는 쪽 클래스 이름(타입 헤더)이 없어서 읽을 타입을 리스너가 정한다
    @KafkaListener(
            topics = WalletKafkaTopics.REFUND_APPROVED,
            groupId = WalletKafkaTopics.CONSUMER_GROUP,
            containerFactory = WalletKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.wallet.adapter.in.event.dto.RefundApprovedMessage")
    void on(RefundApprovedMessage message) {
        // 값이 틀린 메시지는 다시 해도 같으므로 IllegalArgumentException으로 알린다. 지갑 오류 처리기가 다시 시도하지 않고 원문과 함께 실패 기록에 남긴다.
        // 결과 메시지를 보내지 않는 이유: 환불은 금액이 1 이상이 아닌 결과를 받지 않고, 환불이 보내지 않는 값이라 사람이 봐야 한다
        validate(message);
        // DB 오류나 결과 보내기 실패는 예외를 그대로 전파한다. 지갑 오류 처리기가 다시 시도하고, 그때 서비스가 처음 결과를 다시 보낸다
        orderRefundUseCase.refund(new OrderRefundCommand(
                message.refundId(), message.orderId(), message.buyerId(), message.amount()));
    }

    // 환불 쪽 약속: 환불·주문·구매자 ID가 있고 amount는 1 이상이다(돌려줄 돈이 0원이면 지갑에 보내지 않는다)
    private static void validate(RefundApprovedMessage message) {
        if (message.refundId() == null) {
            throw invalid("refundId 없음");
        }
        if (message.orderId() == null) {
            throw invalid("orderId 없음");
        }
        if (message.buyerId() == null) {
            throw invalid("buyerId 없음");
        }
        if (message.amount() == null) {
            throw invalid("amount 없음");
        }
        if (message.amount() <= 0) {
            throw invalid("amount가 0 이하");
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("처리할 수 없는 환불 반환 요청입니다. reason=" + reason);
    }
}
