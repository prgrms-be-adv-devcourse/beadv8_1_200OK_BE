package com.ok.delivery.repository;

import com.ok.delivery.domain.RemoteAreaPostalCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RemoteAreaPostalCodeRepository extends JpaRepository<RemoteAreaPostalCode, String> {

    Optional<RemoteAreaPostalCode> findByPostalCodeAndDeletedAtIsNull(String postalCode);
}
