package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.application.port.out.RemoteAreaPostalCodeRepository;
import com.ok.delivery.domain.RemoteAreaPostalCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RemoteAreaPostalCodeJpaRepository
        extends JpaRepository<RemoteAreaPostalCode, String>, RemoteAreaPostalCodeRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    <S extends RemoteAreaPostalCode> S saveAndFlush(S entity);

    // 잠금 조회의 계약(첫 쿼리로 호출)은 포트 메서드에 적혀 있다
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RemoteAreaPostalCode p where p.postalCode = :postalCode")
    Optional<RemoteAreaPostalCode> findByIdForUpdate(@Param("postalCode") String postalCode);
}
