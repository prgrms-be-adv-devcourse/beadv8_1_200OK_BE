package com.ok.member.auth.application.port.out;

import com.ok.member.auth.domain.RefreshToken;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Refresh Token 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 * Spring Data에서 물려받던 메서드도 서비스가 쓰는 것만 같은 시그니처로 둔다. 구현체가 Spring Data 프록시라 동작은 그대로다.
 */
public interface RefreshTokenRepository {

    /**
     * 지금 쓸 수 있는 토큰의 해시로 기록의 id만 잠금 없이 조회한다. 재발급에서 {@link #findForUpdateById}로 잠그기 전에 쓴다.
     *
     * <p>엔티티가 아니라 id만 받는 이유: 엔티티를 먼저 읽으면 영속성 컨텍스트에 남아, 뒤의 잠금 조회가 행을 잠그기만 하고
     * 다른 요청이 그사이 바꾼 해시를 다시 읽지 않는다. 행을 지우지 않고 해시를 바꾸는 회전에서는 그러면 같은 토큰이 두 번 쓰인다.
     * 같은 이유로 해시로 엔티티를 바로 읽는 조회는 포트에 두지 않는다.
     * 구현은 {@code RefreshTokenJpaRepository}가 쿼리를 다시 선언해 만든다.
     */
    Optional<Long> findIdByTokenHash(String tokenHash);

    /**
     * 기본 키로 저장 기록을 조회하면서 쓰기 잠금을 건다. 재발급에서 한 토큰이 한 번만 쓰이도록 할 때 사용한다.
     * 포트는 JPA를 몰라 잠금을 선언하지 못하므로, 잠금은 구현({@code RefreshTokenJpaRepository})이 다시 선언해 건다.
     *
     * <p>같은 토큰으로 재발급 요청이 동시에 오면 뒤의 요청은 앞의 트랜잭션이 끝날 때까지 기다린다.
     * 앞의 요청이 토큰을 회전하고 커밋하면, 뒤의 요청은 바뀐 해시를 읽어 재발급에 실패한다.
     *
     * <p>해시가 아니라 기본 키로 잠그는 이유: 있는 행을 기본 키로 잠그면 그 행만 잠긴다.
     * 없는 해시로 잠금 조회를 하면 인덱스 구간이 잠겨 다른 토큰 저장을 막을 수 있어, 해시로는 잠금 없이 먼저 찾는다.
     */
    Optional<RefreshToken> findForUpdateById(Long id);

    <S extends RefreshToken> S save(S refreshToken);

    /**
     * 기준 시각에 이미 만료된 기록을 만료 일시가 이른 순서로 최대 {@code batchSize}개 지운다.
     * 한 번에 다 지우면 긴 트랜잭션이 잠금과 undo 로그를 오래 쥐므로, 호출하는 쪽이 짧은 트랜잭션으로 나눠 반복한다.
     *
     * @return 지운 행 수. {@code batchSize}보다 작으면 더 지울 행이 없다
     */
    int deleteExpiredBatch(LocalDateTime now, int batchSize);

}
