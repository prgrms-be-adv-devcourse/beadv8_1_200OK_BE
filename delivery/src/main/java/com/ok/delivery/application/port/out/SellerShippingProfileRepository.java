package com.ok.delivery.application.port.out;

import com.ok.delivery.domain.SellerShippingProfile;

import java.util.Collection;
import java.util.List;

/**
 * 판매자 배송 설정 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface SellerShippingProfileRepository {

    List<SellerShippingProfile> findAllBySellerIdIn(Collection<Long> sellerIds);
}
