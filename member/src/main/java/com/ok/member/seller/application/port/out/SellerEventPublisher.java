package com.ok.member.seller.application.port.out;

import java.time.LocalDateTime;

/**
 * 판매자 하위 도메인의 이벤트 발행 인터페이스. 서비스는 이 인터페이스로만 이벤트를 발행한다.
 *
 * <p>회원(member.member)과 판매자는 같은 모듈이지만 역할과 성격이 달라, 판매자 기능이 늘어날 때
 * 회원의 발행 인터페이스가 함께 커지지 않도록 하위 도메인마다 인터페이스를 둔다.
 * 발행 방식은 구현체가 맡고, 메시지 클래스는 구현체 쪽에서만 만들도록 값을 받는다.
 *
 * <p>"판매자가 승인됐다"는 사실 자체를 알리던 스프링 이벤트는 받는 모듈이 없어 지웠다(#587, 지갑 담당과 합의).
 * 승인 때는 출고지 우편번호만 보낸다. 지갑이 출금·권한 회수를 만들 때 승인 알림이 필요해지면 그때 메시지 약속을 정해 이 인터페이스에 더한다.
 */
public interface SellerEventPublisher {

    /**
     * 판매자의 현재 출고지 우편번호를 delivery에 알린다 (#587). 판매자 승인(신청서의 처음 값), 프로필 등록, 프로필 수정 때마다 부른다(#651).
     * 승인·프로필을 저장하는 트랜잭션 안에서 불러야 한다. 구현체가 보낼 기록(outbox)을 그 트랜잭션에 남겨, 롤백되면 보내지 않는다.
     *
     * <p>이름의 Changed는 "우편번호가 바뀔 때만 보낸다"던 처음 설계(#587)에서 남은 것이다. 지금은 바뀌지 않아도 저장마다 부른다.
     * outbox 종류 이름({@code SELLER_ORIGIN_POSTAL_CODE_CHANGED})이 DB에 문자열로 쌓여 있어 그 이름과 맞춰 두었다.
     *
     * @param sellerId   판매자인 회원의 ID (회원 ID와 같은 값)
     * @param occurredAt 이 우편번호를 저장한 시각. delivery는 이 값으로 늦게 온 옛 메시지를 버린다
     */
    void publishOriginPostalCodeChanged(Long sellerId, String postalCode, LocalDateTime occurredAt);

}
