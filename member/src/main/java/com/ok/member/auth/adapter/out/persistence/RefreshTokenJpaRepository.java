package com.ok.member.auth.adapter.out.persistence;

import com.ok.member.auth.application.port.out.RefreshTokenRepository;
import com.ok.member.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

/**
 * {@link RefreshTokenRepository}의 Spring Data JPA 구현. 쿼리 메서드는 포트에 선언한 이름으로 만들어진다.
 */
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshToken, Long>, RefreshTokenRepository {

    // 포트의 선언만으로도 쿼리가 만들어져, 여기서 다시 선언하지 않으면 잠금 없는 조회로 오류 없이 돈다
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findForUpdateById(Long id);

    // JpaRepository와 포트가 같은 메서드를 다른 제네릭 형태로 물려줘 이 타입으로 부르면 모호한 호출이 된다.
    // 여기서 다시 선언해 하나로 합친다. 구현은 그대로 Spring Data 기본 구현이다
    @Override
    <S extends RefreshToken> S save(S refreshToken);

}
