package com.ok.member.auth.repository;

import com.ok.member.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * 토큰 해시로 저장 기록을 조회한다.
     * 클라이언트가 보낸 원문을 같은 방식으로 해시한 뒤 이 메서드로 찾는다. (재발급·로그아웃 이슈에서 사용)
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

}
