package com.ok.member.member.application.port.out;

import com.ok.member.member.domain.MemberOutbox;
import com.ok.member.member.domain.MemberOutboxStatus;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * 회원 outbox 저장소 출력 포트. 구현은 {@code adapter/out/persistence}의 Spring Data 저장소다.
 */
public interface MemberOutboxRepository {

    MemberOutbox save(MemberOutbox outbox);

    Optional<MemberOutbox> findById(Long id);

    /**
     * 주어진 상태의 줄을 쌓인 순서(id)대로 읽는다. 같은 회원의 메시지가 기록된 순서대로 나가게 하려는 것이다.
     */
    List<MemberOutbox> findByStatusOrderByIdAsc(MemberOutboxStatus status, Pageable pageable);

}
