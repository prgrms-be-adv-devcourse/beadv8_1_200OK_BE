package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.domain.SellerShippingProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SellerShippingProfileRepository extends JpaRepository<SellerShippingProfile, Long> {

    List<SellerShippingProfile> findAllBySellerIdIn(Collection<Long> sellerIds);
}
