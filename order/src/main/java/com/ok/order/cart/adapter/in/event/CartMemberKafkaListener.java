package com.ok.order.cart.adapter.in.event;

import com.ok.order.cart.adapter.in.event.dto.MemberRegisteredMessage;
import com.ok.order.cart.application.port.in.CreateCartUseCase;
import com.ok.order.order.adapter.in.event.OrderKafkaConsumerConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 회원이 Kafka로 보내는 회원가입을 받아 장바구니를 만든다(#601).
 * 같은 회원가입이 여러 번 와도 "없으면 저장" 한 번이라 장바구니는 하나만 생긴다.
 * 메시지를 끝내 처리하지 못해도 장바구니는 처음 담을 때 만들어지므로 구매자가 막히지 않는다.
 * 처리 중 예외는 주문 오류 처리기({@link OrderKafkaConsumerConfig})가 다시 시도한 뒤 실패 기록 표에 남긴다.
 */
@Component
@RequiredArgsConstructor
class CartMemberKafkaListener {

    private final CreateCartUseCase createCart;

    // 메시지에 보내는 쪽 클래스 이름(타입 헤더)이 없어서 읽을 타입을 리스너가 정한다
    @KafkaListener(
            topics = CartInboundTopics.MEMBER_REGISTERED,
            groupId = CartInboundTopics.CART_CREATE_GROUP,
            containerFactory = OrderKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
            properties = "spring.json.value.default.type=com.ok.order.cart.adapter.in.event.dto.MemberRegisteredMessage")
    void on(MemberRegisteredMessage message) {
        // 회원 ID가 없으면 다시 받아도 처리할 수 없다. IllegalArgumentException은 오류 처리기가 다시 시도하지 않고 바로 실패 기록에 남긴다
        if (message.memberId() == null) {
            throw new IllegalArgumentException("회원가입 메시지에 회원 ID가 없습니다.");
        }
        createCart.createCart(message.memberId());
    }
}
