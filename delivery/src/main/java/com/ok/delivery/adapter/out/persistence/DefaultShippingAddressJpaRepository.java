package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.application.port.out.DefaultShippingAddressRepository;
import com.ok.delivery.domain.DefaultShippingAddress;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DefaultShippingAddressJpaRepository
        extends JpaRepository<DefaultShippingAddress, Long>, DefaultShippingAddressRepository {
}
