package com.ok.member.member.application.port.in;

import java.time.LocalDateTime;

/**
 * member 모듈의 다른 하위 도메인이 보낼 메시지를 회원 outbox에 남긴다 (#587).
 * outbox는 회원 하위 도메인에 있어, 판매자 하위 도메인은 자기 출력 어댑터에서 이 유스케이스로만 줄을 남긴다(하위 도메인 간 호출 규칙).
 *
 * <p>호출한 쪽의 트랜잭션 안에서만 기록한다. 트랜잭션 없이 부르면 예외가 난다.
 * 판매자 프로필 저장과 outbox 줄이 함께 커밋·롤백돼야 롤백된 변경이 나가지 않기 때문이다.
 */
public interface MemberOutboxRecordUseCase {

    /**
     * 출고지 우편번호 변경 메시지를 남긴다. 30초 주기 발송 작업이 커밋 뒤에 보낸다.
     *
     * @param sellerId   판매자인 회원의 ID
     * @param occurredAt 우편번호가 바뀐 시각
     */
    void recordSellerOriginPostalCodeChanged(Long sellerId, String postalCode, LocalDateTime occurredAt);

}
