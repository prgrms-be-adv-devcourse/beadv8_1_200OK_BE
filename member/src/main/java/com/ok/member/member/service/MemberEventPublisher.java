package com.ok.member.member.service;

/**
 * 회원 모듈의 이벤트 발행 인터페이스. 서비스는 이 인터페이스로만 이벤트를 발행한다.
 *
 * <p>발행 방식(스프링 이벤트, 메시지 브로커 등)은 구현체({@code event} 패키지)가 맡아, 방식을 바꿔도 서비스는 바뀌지 않는다.
 * 이벤트 클래스는 구현체 안에서만 만들도록 값을 받는다. (컨벤션: structure.md)
 */
public interface MemberEventPublisher {

    /**
     * 회원가입 완료를 알린다. 다른 모듈은 이 이벤트로 장바구니·구매자 지갑 등을 만든다.
     */
    void publishMemberRegistered(Long memberId);

}
