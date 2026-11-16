package com.ok.member.member.application.port.out;

import com.ok.member.member.domain.Member;

import java.util.Optional;

/**
 * 회원 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 * Spring Data에서 물려받던 메서드도 서비스가 쓰는 것만 같은 시그니처로 둔다. 구현체가 Spring Data 프록시라 동작은 그대로다.
 */
public interface MemberRepository {

    Optional<Member> findById(Long id);

    boolean existsByLoginId(String loginId);

    boolean existsByEmail(String email);

    Optional<Member> findByLoginId(String loginId);

    <S extends Member> S save(S member);

    <S extends Member> S saveAndFlush(S member);

}
