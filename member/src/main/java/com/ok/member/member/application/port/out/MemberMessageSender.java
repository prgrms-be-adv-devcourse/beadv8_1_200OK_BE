package com.ok.member.member.application.port.out;

/**
 * 회원 outbox에 쌓인 메시지를 메시지 브로커로 보내는 인터페이스 (#560).
 *
 * <p>서비스가 이벤트를 알리는 {@link MemberEventPublisher}와 나눈 이유: 그쪽은 회원 변경 트랜잭션 안에서 "보낼 것"을 남기고,
 * 이쪽은 커밋된 뒤 outbox 발송 작업이 실제로 보낸다. 실패하면 예외로 알려 그 줄을 다음 실행에서 다시 보내게 한다.
 * 보내는 방식(Kafka)은 구현체({@code adapter.out.event} 패키지)가 맡는다.
 */
public interface MemberMessageSender {

    /**
     * 회원가입을 알리는 메시지를 보낸다. 브로커가 저장을 확인할 때까지 기다린다.
     *
     * @throws IllegalStateException 브로커가 거절했거나 정해진 시간 안에 확인이 오지 않으면. 메시지는 나갔을 수도 있다
     */
    void sendMemberRegistered(Long memberId);

}
