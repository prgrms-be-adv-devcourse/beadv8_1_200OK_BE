package com.ok.member.member.adapter.out.persistence;

import com.ok.member.member.application.port.out.MemberOutboxRepository;
import com.ok.member.member.domain.MemberOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 회원 outbox 저장소 출력 포트({@link MemberOutboxRepository})의 JPA 구현.
 */
public interface MemberOutboxJpaRepository extends JpaRepository<MemberOutbox, Long>, MemberOutboxRepository {

    // 포트와 JpaRepository가 같은 메서드를 다른 제네릭 형태로 물려줘 모호한 호출이 되므로 다시 선언해 하나로 합친다

    @Override
    MemberOutbox save(MemberOutbox outbox);

    @Override
    Optional<MemberOutbox> findById(Long id);

}
