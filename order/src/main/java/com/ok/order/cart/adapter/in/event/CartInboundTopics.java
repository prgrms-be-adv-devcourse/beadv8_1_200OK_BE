package com.ok.order.cart.adapter.in.event;

/**
 * 장바구니가 Kafka로 받는 토픽 이름과 장바구니 리스너의 컨슈머 그룹. 토픽 이름은 회원과의 약속이라 회원과 같은 값이어야 받는다(#601).
 * 회원 클래스를 import하지 않고 같은 문자열을 여기 따로 둔다(모듈 경계).
 */
final class CartInboundTopics {

    // 회원 → 장바구니: 회원가입. 키는 memberId
    static final String MEMBER_REGISTERED = "member.member-registered.v1";

    // 그룹 이름은 <모듈>.<용도>(#648). 회원가입을 받는 다른 모듈과 메시지를 나눠 갖지 않고 각자 모두 받게 모듈마다 나눈다
    static final String CART_CREATE_GROUP = "order.cart-create";

    private CartInboundTopics() {
    }
}
