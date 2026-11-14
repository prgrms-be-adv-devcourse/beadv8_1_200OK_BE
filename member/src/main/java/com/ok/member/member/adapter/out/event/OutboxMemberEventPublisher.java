package com.ok.member.member.adapter.out.event;

import com.ok.common.event.DomainEvent;
import com.ok.common.event.MemberRegisteredEvent;
import com.ok.member.member.application.port.out.MemberEventPublisher;
import com.ok.member.member.application.port.out.MemberOutboxRepository;
import com.ok.member.member.domain.MemberOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 회원 모듈 이벤트를 outbox에 남겨 다른 서비스로 보낸다 (#560).
 *
 * <p>호출한 서비스의 트랜잭션 안에서 outbox 줄({@link MemberOutbox})을 저장한다. 그래서 회원 변경이 커밋되면 반드시 보내고,
 * 롤백되면 보내지 않는다. 실제 Kafka 발송은 {@code MemberOutboxRelayScheduler}가 커밋 뒤에 따로 한다.
 *
 * <p>장바구니가 아직 스프링 이벤트로 받으므로, 옮길 때까지 같은 트랜잭션에서 스프링 이벤트({@link DomainEvent})도 낸다.
 * 구독하는 모듈은 커밋 후에 처리한다({@code @ApplicationModuleListener}).
 */
@Component
@RequiredArgsConstructor
public class OutboxMemberEventPublisher implements MemberEventPublisher {

    // 저장은 영속 포트로 한다. JPA 저장소를 직접 쓰면 이 어댑터가 영속 기술에 묶인다
    private final MemberOutboxRepository memberOutboxRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publishMemberRegistered(Long memberId) {
        // 이벤트를 먼저 만들어 값 검사(회원 ID 필수)를 통과한 뒤에만 outbox 줄을 남긴다
        MemberRegisteredEvent event = new MemberRegisteredEvent(memberId);
        memberOutboxRepository.save(MemberOutbox.memberRegistered(memberId));
        // TODO(#560): 장바구니가 Kafka(member.member-registered)로 받게 되면 스프링 이벤트 발행과 common의 MemberRegisteredEvent를 지운다.
        //  클래스를 먼저 지우면 장바구니 리스너가 컴파일되지 않는다. 장바구니 전환 이슈가 생기면 번호를 바꾼다
        publish(event);
    }

    // 모듈 간 계약으로 정한 이벤트만 나가도록 DomainEvent만 받는다
    private void publish(DomainEvent event) {
        applicationEventPublisher.publishEvent(event);
    }

}
