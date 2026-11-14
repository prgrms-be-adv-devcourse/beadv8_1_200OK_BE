package com.ok.member.member.adapter.out.event;

/**
 * {@link MemberTopics#MEMBER_REGISTERED} 토픽에 보내는 메시지. 이 필드 이름·타입이 장바구니와의 약속이다(#560 메시지 약속).
 * 받는 쪽은 이 클래스가 아니라 JSON 모양으로 읽는다(타입 헤더를 싣지 않음). 필드를 바꾸면 받는 쪽도 같이 바꿔야 한다.
 */
public record MemberRegisteredMessage(Long memberId) {
}
