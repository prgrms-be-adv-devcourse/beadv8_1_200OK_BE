package com.ok.member.auth.application.port.out;

import com.ok.member.auth.domain.RefreshToken;

import java.util.Optional;

/**
 * Refresh Token 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 * Spring Data에서 물려받던 메서드도 서비스가 쓰는 것만 같은 시그니처로 둔다. 구현체가 Spring Data 프록시라 동작은 그대로다.
 */
public interface RefreshTokenRepository {

    /**
     * 토큰 해시로 저장 기록을 조회한다.
     * 클라이언트가 보낸 원문을 같은 방식으로 해시한 뒤 이 메서드로 찾는다. (재발급에서는 잠금 없이 먼저 찾은 뒤 findForUpdateById로 잠근다)
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 기본 키로 저장 기록을 조회하면서 쓰기 잠금을 건다. 재발급에서 한 토큰이 한 번만 쓰이도록 할 때 사용한다.
     * 포트는 JPA를 몰라 잠금을 선언하지 못하므로, 잠금은 구현({@code RefreshTokenJpaRepository})이 다시 선언해 건다.
     *
     * <p>같은 토큰으로 재발급 요청이 동시에 오면 뒤의 요청은 앞의 트랜잭션이 끝날 때까지 기다린다.
     * 앞의 요청이 토큰을 지우고 커밋하면, 뒤의 요청은 기록을 찾지 못해 재발급에 실패한다.
     *
     * <p>해시가 아니라 기본 키로 잠그는 이유: 있는 행을 기본 키로 잠그면 그 행만 잠긴다.
     * 없는 해시로 잠금 조회를 하면 인덱스 구간이 잠겨 다른 토큰 저장을 막을 수 있어, 해시로는 잠금 없이 먼저 찾는다.
     */
    Optional<RefreshToken> findForUpdateById(Long id);

    <S extends RefreshToken> S save(S refreshToken);

    void delete(RefreshToken refreshToken);

}
