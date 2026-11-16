package com.ok.member.member.application.port.out;

/**
 * 회원 모듈의 이벤트 발행 인터페이스. 서비스는 이 인터페이스로만 이벤트를 발행한다.
 *
 * <p>발행 방식(스프링 이벤트, 메시지 브로커 등)은 구현체({@code adapter.out.event} 패키지)가 맡아, 방식을 바꿔도 서비스는 바뀌지 않는다.
 * 이벤트 클래스는 구현체 안에서만 만들도록 값을 받는다. (member 헥사고날 구조, docs/DEVELOPMENT.md)
 */
public interface MemberEventPublisher {

    /**
     * 회원가입 완료를 알린다. 장바구니가 이 알림으로 회원의 장바구니를 만든다.
     * 가입과 같은 트랜잭션에서 불러야 한다. 구현체가 보낼 기록(outbox)을 그 트랜잭션에 남겨, 롤백되면 보내지 않는다.
     */
    void publishMemberRegistered(Long memberId);

}
