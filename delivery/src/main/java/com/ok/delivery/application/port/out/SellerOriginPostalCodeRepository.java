package com.ok.delivery.application.port.out;

import com.ok.delivery.domain.SellerOriginPostalCode;

import java.util.Optional;

/**
 * 판매자 출고지 우편번호 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface SellerOriginPostalCodeRepository {

    Optional<SellerOriginPostalCode> findBySellerId(Long sellerId);

    <S extends SellerOriginPostalCode> S save(S entity);
}
