package com.ok.delivery.application.port.out;

import com.ok.delivery.domain.DefaultShippingAddress;

import java.util.Optional;

/**
 * 기본 배송지 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface DefaultShippingAddressRepository {

    Optional<DefaultShippingAddress> findByMemberId(Long memberId);

    <S extends DefaultShippingAddress> S save(S entity);
}
