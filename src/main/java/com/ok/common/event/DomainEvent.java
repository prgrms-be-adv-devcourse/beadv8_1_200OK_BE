package com.ok.common.event;

/**
 * 모듈 간에 주고받는 이벤트임을 나타낸다.
 * 각 모듈의 발행 구현체(event 패키지)는 이 타입만 발행해, 아무 객체나 이벤트로 나가지 않게 한다.
 */
public interface DomainEvent {
}
