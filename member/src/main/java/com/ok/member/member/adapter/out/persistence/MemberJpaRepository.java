package com.ok.member.member.adapter.out.persistence;

import com.ok.member.member.application.port.out.MemberRepository;
import com.ok.member.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@link MemberRepository}의 Spring Data JPA 구현. 쿼리 메서드는 포트에 선언한 이름으로 만들어진다.
 */
public interface MemberJpaRepository extends JpaRepository<Member, Long>, MemberRepository {

    // JpaRepository와 포트가 같은 메서드를 다른 제네릭 형태로 물려줘 이 타입으로 부르면 모호한 호출이 된다.
    // 여기서 다시 선언해 하나로 합친다. 구현은 그대로 Spring Data 기본 구현이다
    @Override
    Optional<Member> findById(Long id);

    @Override
    <S extends Member> S save(S member);

    @Override
    <S extends Member> S saveAndFlush(S member);

}
