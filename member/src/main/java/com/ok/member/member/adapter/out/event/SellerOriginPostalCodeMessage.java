package com.ok.member.member.adapter.out.event;

import java.time.LocalDateTime;

/**
 * {@link MemberTopics#SELLER_ORIGIN_POSTAL_CODE} 토픽에 보내는 메시지. 이 필드 이름·타입이 delivery와의 약속이다(#587, #558).
 * 받는 쪽은 이 클래스가 아니라 JSON 모양으로 읽는다(타입 헤더를 싣지 않음). 필드를 바꾸면 받는 쪽도 같이 바꿔야 한다.
 * outbox에도 이 모양의 JSON으로 저장했다가, 보낼 때 다시 읽어 그대로 보낸다.
 *
 * @param sellerId   판매자인 회원의 ID (member의 회원 ID와 같은 값)
 * @param postalCode 출고지 우편번호 (5자리 숫자)
 * @param occurredAt 우편번호가 바뀐 시각 (한국 시간, 시간대 표기 없음). 정산 → 지갑 메시지의 시각과 같은 모양
 */
public record SellerOriginPostalCodeMessage(Long sellerId, String postalCode, LocalDateTime occurredAt) {
}
