package com.ok.delivery.adapter.in.event;

/**
 * 배송이 Kafka로 받는 토픽 이름과 컨슈머 그룹을 한곳에 둔다.
 * 토픽 이름은 보내는 모듈(회원·주문)과의 약속이라 리스너와 토픽 선언이 같은 값을 쓰도록 상수로 둔다. 이름 규칙은 {@code <보내는 모듈>.<이벤트>}이다.
 */
public final class DeliveryInboundTopics {

    /** 회원 → 배송: 판매자 출고지 우편번호가 바뀜. 키는 sellerId (#558, #587 메시지 약속) */
    public static final String SELLER_ORIGIN_POSTAL_CODE = "member.seller-origin-postal-code.v1";

    /** 주문 → 배송: 결제가 완료됨. 키는 orderId */
    public static final String ORDER_PAID = "order.order-paid.v1";

    // 그룹은 리스너마다 하나씩, 이름은 <모듈>.<용도>로 둔다.
    // 오프셋이 그룹마다 따로라 한 리스너만 처음부터 다시 받거나 오프셋을 옮겨도 다른 리스너는 그대로이고,
    // 한 리스너의 리밸런스가 다른 리스너를 멈추지 않으며, 랙 지표에서 누가 무슨 일로 밀렸는지 바로 보인다.
    // 서버 이름은 넣지 않는다: 리스너가 다른 서버로 옮겨 가도 그룹 이름이 같아야 오프셋을 이어 받는다(이름이 바뀌면 처음부터 다시 읽는다).
    // 같은 그룹 안에서는 배송 서버가 여러 대여도 한 메시지를 한 대만 처리한다

    /** 판매자 출고지 우편번호를 받아 배송 쪽 사본에 저장하는 리스너의 그룹 */
    public static final String SELLER_ORIGIN_SYNC_GROUP = "delivery.seller-origin-sync";

    /**
     * 처리하지 못한 출고지 우편번호 메시지를 옮겨 두는 토픽(DLT). {@code <받은 토픽>.<그룹>.DLT} 형식이다.
     * 그룹 이름을 넣는 이유: 같은 토픽을 받는 다른 그룹의 실패와 섞이지 않고, 다시 넣을 때 그 그룹만 다시 받게 하려는 것이다.
     */
    public static final String SELLER_ORIGIN_POSTAL_CODE_DLT = SELLER_ORIGIN_POSTAL_CODE + "." + SELLER_ORIGIN_SYNC_GROUP + ".DLT";

    /** 결제 완료를 받아 shipment를 만드는 리스너의 그룹 */
    public static final String SHIPMENT_CREATE_GROUP = "delivery.shipment-create";

    /**
     * 처리하지 못한 결제 완료 메시지를 옮겨 두는 토픽(DLT). {@code <받은 토픽>.<그룹>.DLT} 형식이다.
     * 그룹 이름을 넣는 이유: 같은 토픽을 받는 다른 그룹의 실패와 섞이지 않고, 다시 넣을 때 그 그룹만 다시 받게 하려는 것이다.
     */
    public static final String ORDER_PAID_DLT = ORDER_PAID + "." + SHIPMENT_CREATE_GROUP + ".DLT";

    private DeliveryInboundTopics() {
    }
}
