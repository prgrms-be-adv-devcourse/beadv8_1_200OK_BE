package com.ok.member.member.application.port.out;

import com.ok.member.member.application.dto.TokenVersionChange;
import com.ok.member.member.domain.Member;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 회원 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 * Spring Data에서 물려받던 메서드도 서비스가 쓰는 것만 같은 시그니처로 둔다. 구현체가 Spring Data 프록시라 동작은 그대로다.
 */
public interface MemberRepository {

    Optional<Member> findById(Long id);

    /**
     * 회원을 쓰기 잠금으로 조회한다. 잠금은 호출한 트랜잭션이 끝날 때까지 유지된다.
     * 같은 회원의 비밀번호 변경이 동시에 들어오면 뒤 요청이 앞 요청의 커밋을 기다렸다가 바뀐 해시를 읽게 한다.
     */
    Optional<Member> findForUpdateById(Long id);

    /**
     * 기준 시각 뒤에 토큰 버전을 올린 회원의 id, 버전, 변경 시각만 조회한다. {@code idx_member_token_version_changed_at} 인덱스를 탄다.
     * 최근 Access Token 수명 동안 비밀번호를 바꾼 회원만 나오므로 건수가 적다.
     * 30초마다 도는 다시 채우기가 쓰므로 엔티티(비밀번호 해시 포함)를 통째로 읽지 않는다.
     */
    List<TokenVersionChange> findTokenVersionChangesSince(LocalDateTime since);

    boolean existsByLoginId(String loginId);

    boolean existsByEmail(String email);

    Optional<Member> findByLoginId(String loginId);

    <S extends Member> S save(S member);

    <S extends Member> S saveAndFlush(S member);

}
