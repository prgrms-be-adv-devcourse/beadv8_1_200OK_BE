package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.application.port.out.SellerOriginPostalCodeRepository;
import com.ok.delivery.domain.SellerOriginPostalCode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerOriginPostalCodeJpaRepository
        extends JpaRepository<SellerOriginPostalCode, Long>, SellerOriginPostalCodeRepository {
}
