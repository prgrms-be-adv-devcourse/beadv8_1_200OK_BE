package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.application.port.out.SellerShippingProfileRepository;
import com.ok.delivery.domain.SellerShippingProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerShippingProfileJpaRepository
        extends JpaRepository<SellerShippingProfile, Long>, SellerShippingProfileRepository {
}
