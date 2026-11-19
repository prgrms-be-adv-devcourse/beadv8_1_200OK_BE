package com.ok.wallet.adapter.in.event;

import com.ok.wallet.adapter.in.event.dto.RefundWalletRevertRequestedMessage;
import com.ok.wallet.application.dto.OrderRefundRevertCommand;
import com.ok.wallet.application.port.in.OrderRefundRevertUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 환불이 Kafka로 보내는 되돌리기 요청을 받아 돌려준 예치금을 다시 가져오고 결과를 환불에 보낸다.
 * 같은 환불이 여러 번 와도 서비스가 refundId로 한 번만 가져오고, 올 때마다 처음 결과를 다시 보낸다.
 * 이름 앞에 Wallet을 붙인 이유: 주문 모듈에도 같은 요청을 받는 RefundRevertKafkaListener가 있어 한 앱(monolith)에서 빈 이름이 겹친다.
 */
@Component
@RequiredArgsConstructor
public class WalletRefundRevertKafkaListener {
    private final OrderRefundRevertUseCase orderRefundRevertUseCase;

    // 메시지에 보내는 쪽 클래스 이름(타입 헤더)이 없어서 읽을 타입을 리스너가 정한다
    @KafkaListener(
            topics = WalletKafkaTopics.REFUND_WALLET_REVERT_REQUESTED,
            groupId = WalletKafkaTopics.REFUND_REVERT_GROUP,
            containerFactory = WalletKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.wallet.adapter.in.event.dto.RefundWalletRevertRequestedMessage")
    void on(RefundWalletRevertRequestedMessage message) {
        // 값이 틀린 메시지와 DB 오류·결과 보내기 실패를 다루는 방식은 반환 요청 리스너(RefundKafkaListener)와 같다
        validate(message);
        orderRefundRevertUseCase.revert(new OrderRefundRevertCommand(
                message.refundId(), message.orderId(), message.buyerId(), message.amount()));
    }

    // 환불 쪽 약속: 환불·주문·구매자 ID가 있고 amount는 1 이상이다(반환 요청과 같은 값으로 보낸다)
    private static void validate(RefundWalletRevertRequestedMessage message) {
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
        return new IllegalArgumentException("처리할 수 없는 환불 되돌리기 요청입니다. reason=" + reason);
    }
}
