package com.ok.order.cart.adapter.in.event.dto;

/**
 * 회원이 {@code member.member-registered.v1}로 보내는 회원가입을 장바구니가 읽는 모양. 필드 이름·타입은 회원과의 약속이다(#560, #601).
 * 회원 클래스를 import하지 않고 같은 JSON 모양의 장바구니 DTO로 읽는다.
 */
public record MemberRegisteredMessage(Long memberId) {
}
