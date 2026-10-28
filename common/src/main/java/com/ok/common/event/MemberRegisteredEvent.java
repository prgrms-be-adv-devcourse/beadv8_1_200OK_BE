package com.ok.common.event;

import java.util.Objects;

/**
 * 회원가입 완료 이벤트. 다른 모듈은 이 이벤트를 구독해 후속 처리를 한다.
 */
public record MemberRegisteredEvent(Long memberId) implements DomainEvent {

    public MemberRegisteredEvent {
        // 회원 id가 없으면 구독하는 모듈이 처리할 수 없으므로 만들 때 막는다
        Objects.requireNonNull(memberId, "memberId는 필수입니다.");
    }

}
