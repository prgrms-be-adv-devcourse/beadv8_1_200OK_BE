package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.application.port.out.RegionGroupRepository;
import com.ok.delivery.domain.RegionGroup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionGroupJpaRepository extends JpaRepository<RegionGroup, String>, RegionGroupRepository {
}
