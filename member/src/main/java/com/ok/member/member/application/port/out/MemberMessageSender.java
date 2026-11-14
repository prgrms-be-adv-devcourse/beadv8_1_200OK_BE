package com.ok.member.member.application.port.out;

/**
 * 회원 outbox에 쌓인 메시지를 메시지 브로커로 보내는 인터페이스 (#560, #587).
 *
 * <p>서비스가 이벤트를 알리는 {@link MemberEventPublisher}와 나눈 이유: 그쪽은 회원 변경 트랜잭션 안에서 "보낼 것"을 남기고,
 * 이쪽은 커밋된 뒤 outbox 발송 작업이 실제로 보낸다. 실패하면 예외로 알려 그 줄을 다음 실행에서 다시 보내게 한다.
 * 보내는 방식(Kafka)은 구현체({@code adapter.out.event} 패키지)가 맡는다.
 * 메서드는 모두 브로커가 저장을 확인할 때까지 기다리고, 실패하면 {@link IllegalStateException}을 던진다. 그때도 메시지는 나갔을 수 있다.
 */
public interface MemberMessageSender {

    /**
     * 회원가입을 알리는 메시지를 보낸다.
     */
    void sendMemberRegistered(Long memberId);

    /**
     * 판매자 출고지 우편번호 변경 메시지를 보낸다 (#587).
     *
     * @param sellerId 메시지 키. 판매자인 회원의 ID
     * @param payload  outbox에 저장해 둔 본문({@link MemberMessagePayloadWriter}가 만든 JSON)
     */
    void sendSellerOriginPostalCodeChanged(Long sellerId, String payload);

}
