package com.ok.member.seller.adapter.out.member;

import com.ok.member.member.application.port.in.MemberOutboxRecordUseCase;
import com.ok.member.seller.application.port.out.SellerEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 판매자 하위 도메인의 메시지를 회원 하위 도메인의 outbox에 남긴다 (#587).
 * outbox(표·30초 발송 작업·Kafka 발송기)는 회원 하위 도메인에 있어, 하위 도메인 간 호출 규칙대로 이 출력 어댑터에서만 그 유스케이스를 부른다.
 * 판매자 서비스는 {@link SellerEventPublisher}만 알고, outbox나 Kafka를 모른다.
 * 기록은 부른 쪽(프로필 저장)의 트랜잭션에서만 된다. 트랜잭션 없이 부르면 유스케이스가 예외를 낸다.
 */
@Component
@RequiredArgsConstructor
public class MemberOutboxAdapter implements SellerEventPublisher {

    private final MemberOutboxRecordUseCase memberOutboxRecordUseCase;

    @Override
    public void publishOriginPostalCodeChanged(Long sellerId, String postalCode, LocalDateTime occurredAt) {
        memberOutboxRecordUseCase.recordSellerOriginPostalCodeChanged(sellerId, postalCode, occurredAt);
    }
}
