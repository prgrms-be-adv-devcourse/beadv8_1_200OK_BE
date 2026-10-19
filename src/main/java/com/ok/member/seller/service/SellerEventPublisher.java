package com.ok.member.seller.service;

import java.time.LocalDateTime;

/**
 * 판매자 하위 도메인의 이벤트 발행 인터페이스. 서비스는 이 인터페이스로만 이벤트를 발행한다.
 *
 * <p>회원(member.member)과 판매자는 같은 모듈이지만 역할과 성격이 달라, 판매자 기능이 늘어날 때
 * 회원의 발행 인터페이스가 함께 커지지 않도록 하위 도메인마다 인터페이스를 둔다.
 * 발행 방식(스프링 이벤트, 메시지 브로커 등)은 구현체({@code event} 패키지)가 맡고, 이벤트 클래스는 구현체 안에서만 만들도록 값을 받는다.
 */
public interface SellerEventPublisher {

    /**
     * 판매자 승인을 알린다. 다른 모듈은 이 이벤트로 관련된 작업을 한다.
     *
     * @param approvedAt 관리자가 승인한 일시 (신청에 기록한 승인 일시와 같은 값)
     */
    void publishSellerApproved(Long memberId, LocalDateTime approvedAt);

}
