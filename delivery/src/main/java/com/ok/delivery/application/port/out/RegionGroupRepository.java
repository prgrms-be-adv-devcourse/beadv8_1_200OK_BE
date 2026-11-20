package com.ok.delivery.application.port.out;

import com.ok.delivery.domain.RegionGroup;

import java.util.Optional;

/**
 * 지역 그룹 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface RegionGroupRepository {

    Optional<RegionGroup> findById(String id);

    boolean existsById(String id);
}
