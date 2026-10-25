package com.ok.member.member.repository;

import com.ok.member.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    boolean existsByLoginId(String loginId);

    boolean existsByEmail(String email);

    Optional<Member> findByLoginId(String loginId);

}
