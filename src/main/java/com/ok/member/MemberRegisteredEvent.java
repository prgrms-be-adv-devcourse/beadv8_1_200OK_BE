package com.ok.member;

/**
 * 회원가입 완료 이벤트. 다른 모듈은 이 이벤트를 구독해 후속 처리를 한다.
 */
public record MemberRegisteredEvent(Long memberId, MemberRole memberRole) {
}
