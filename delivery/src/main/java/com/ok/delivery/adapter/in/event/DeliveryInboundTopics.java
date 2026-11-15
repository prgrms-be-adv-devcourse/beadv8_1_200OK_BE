package com.ok.delivery.adapter.in.event;

/**
 * 배송이 Kafka로 받는 토픽 이름과 컨슈머 그룹을 한곳에 둔다.
 * 토픽 이름은 보내는 모듈(주문)과의 약속이라 리스너와 토픽 선언이 같은 값을 쓰도록 상수로 둔다. 이름 규칙은 {@code <보내는 모듈>.<이벤트>}이다.
 */
public final class DeliveryInboundTopics {

    /** 주문 → 배송: 결제가 완료됨. 키는 orderId (#644 메시지 약속) */
    public static final String ORDER_PAID = "order.order-paid";

    // 그룹을 delivery로 고정하는 이유: 같은 토픽을 다른 모듈이 받더라도 그룹이 다르면 메시지가 각자에게 모두 간다.
    // 배송 서버가 여러 대여도 같은 그룹이라 한 메시지는 한 대만 처리한다
    public static final String CONSUMER_GROUP = "delivery";

    /**
     * 처리하지 못한 결제 완료 메시지를 옮겨 두는 토픽(DLT). {@code <받은 토픽>.<그룹>.DLT} 형식이다.
     * 그룹 이름을 넣는 이유: 같은 토픽을 받는 다른 모듈의 실패와 섞이지 않고, 다시 넣을 때 배송만 다시 받게 하려는 것이다.
     */
    public static final String ORDER_PAID_DLT = ORDER_PAID + "." + CONSUMER_GROUP + ".DLT";

    private DeliveryInboundTopics() {
    }
}
