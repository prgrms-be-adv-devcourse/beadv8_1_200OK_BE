package com.ok.member.auth.adapter.out.persistence;

import com.ok.member.MemberSchema;
import com.ok.member.auth.application.port.out.RefreshTokenRepository;
import com.ok.member.auth.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@link RefreshTokenRepository}의 Spring Data JPA 구현. 쿼리 메서드는 포트에 선언한 이름으로 만들어진다.
 */
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshToken, Long>, RefreshTokenRepository {

    // 이름만으로는 id만 고르는 쿼리가 만들어지지 않아(엔티티를 돌려준다) 직접 선언한다
    @Override
    @Query("SELECT r.id FROM RefreshToken r WHERE r.tokenHash = :tokenHash")
    Optional<Long> findIdByTokenHash(@Param("tokenHash") String tokenHash);

    // 포트의 선언만으로도 쿼리가 만들어져, 여기서 다시 선언하지 않으면 잠금 없는 조회로 오류 없이 돈다
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findForUpdateById(Long id);

    // JPQL DELETE는 ORDER BY·LIMIT을 쓸 수 없어 네이티브 쿼리로 쓴다.
    // 네이티브 쿼리는 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다.
    // 만료 일시 인덱스 순서(expires_at, id)로 지운다. id 순서로 지우면 기본 키를 처음부터 훑으며 만료되지 않은 행까지 잠그고,
    // 만료된 행이 묶음보다 적게 남은 마지막 묶음은 테이블 끝까지 훑어 끝 구간을 잠가 그동안 로그인(새 행 저장)이 기다린다.
    // 인덱스 순서면 만료된 구간만 잠그고, 새로 저장·회전되는 행은 만료 일시가 먼 뒤쪽이라 겹치지 않는다.
    // 순서를 정해 두면 정리가 겹쳐 돌아도 잠그는 순서가 엇갈리지 않는다. 트랜잭션은 호출한 서비스가 연다
    @Override
    @Modifying
    @Query(value = "DELETE FROM " + MemberSchema.NAME + ".refresh_token "
            + "WHERE expires_at < :now ORDER BY expires_at, id LIMIT :batchSize", nativeQuery = true)
    int deleteExpiredBatch(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    // JpaRepository와 포트가 같은 메서드를 다른 제네릭 형태로 물려줘 이 타입으로 부르면 모호한 호출이 된다.
    // 여기서 다시 선언해 하나로 합친다. 구현은 그대로 Spring Data 기본 구현이다
    @Override
    <S extends RefreshToken> S save(S refreshToken);

}
