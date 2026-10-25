package com.ok.delivery.repository;

import com.ok.delivery.domain.DefaultShippingAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DefaultShippingAddressRepository extends JpaRepository<DefaultShippingAddress, Long> {

    Optional<DefaultShippingAddress> findByMemberId(Long memberId);
}
