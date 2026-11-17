package com.ok.member.member.adapter.out.event;

import com.ok.member.member.application.port.out.MemberEventPublisher;
import com.ok.member.member.application.port.out.MemberOutboxRepository;
import com.ok.member.member.domain.MemberOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 회원 모듈 이벤트를 outbox에 남겨 다른 서비스로 보낸다 (#560).
 *
 * <p>호출한 서비스의 트랜잭션 안에서 outbox 줄({@link MemberOutbox})을 저장한다. 그래서 회원 변경이 커밋되면 반드시 보내고,
 * 롤백되면 보내지 않는다. 실제 Kafka 발송은 {@code MemberOutboxRelayScheduler}가 커밋 뒤에 따로 한다.
 *
 * <p>스프링 이벤트는 내지 않는다. 모듈을 나누면 스프링 이벤트는 member 앱 밖으로 나가지 않아 다른 모듈이 받을 수 없다.
 */
@Component
@RequiredArgsConstructor
public class OutboxMemberEventPublisher implements MemberEventPublisher {

    // 저장은 영속 포트로 한다. JPA 저장소를 직접 쓰면 이 어댑터가 영속 기술에 묶인다
    private final MemberOutboxRepository memberOutboxRepository;

    @Override
    public void publishMemberRegistered(Long memberId) {
        memberOutboxRepository.save(MemberOutbox.memberRegistered(memberId));
    }

}
