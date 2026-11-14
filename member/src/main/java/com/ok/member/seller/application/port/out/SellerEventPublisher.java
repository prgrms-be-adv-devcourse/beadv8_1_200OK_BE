package com.ok.member.seller.application.port.out;

import java.time.LocalDateTime;

/**
 * 판매자 하위 도메인의 이벤트 발행 인터페이스. 서비스는 이 인터페이스로만 이벤트를 발행한다.
 *
 * <p>회원(member.member)과 판매자는 같은 모듈이지만 역할과 성격이 달라, 판매자 기능이 늘어날 때
 * 회원의 발행 인터페이스가 함께 커지지 않도록 하위 도메인마다 인터페이스를 둔다.
 * 발행 방식은 구현체가 맡고, 메시지 클래스는 구현체 쪽에서만 만들도록 값을 받는다.
 *
 * <p>판매자 승인 알림은 받는 모듈이 없어 지웠다(#587, 지갑 담당과 합의). 지갑이 출금·권한 회수를 만들 때 필요해지면
 * 그때 메시지 약속을 정해 이 인터페이스에 다시 더한다.
 */
public interface SellerEventPublisher {

    /**
     * 출고지 우편번호가 바뀌었음(처음 채워진 경우 포함)을 delivery에 알린다 (#587).
     * 프로필을 저장하는 트랜잭션 안에서 불러야 한다. 구현체가 보낼 기록(outbox)을 그 트랜잭션에 남겨, 롤백되면 보내지 않는다.
     *
     * @param sellerId   판매자인 회원의 ID (회원 ID와 같은 값)
     * @param occurredAt 우편번호가 바뀐 시각. delivery는 이 값으로 늦게 온 옛 메시지를 버린다
     */
    void publishOriginPostalCodeChanged(Long sellerId, String postalCode, LocalDateTime occurredAt);

}
