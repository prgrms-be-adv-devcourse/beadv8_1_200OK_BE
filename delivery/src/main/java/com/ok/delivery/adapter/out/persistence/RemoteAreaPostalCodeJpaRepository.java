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
        extends JpaRepository<RemoteAreaPostalCode, String>,
        RemoteAreaPostalCodeRepository,
        RemoteAreaPostalCodeQuerydslRepository {

    @Override
    <S extends RemoteAreaPostalCode> S saveAndFlush(S entity);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RemoteAreaPostalCode p where p.postalCode = :postalCode")
    Optional<RemoteAreaPostalCode> findByIdForUpdate(@Param("postalCode") String postalCode);
}
