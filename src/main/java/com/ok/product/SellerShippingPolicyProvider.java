package com.ok.product;

import java.util.Collection;
import java.util.Map;

/**
 * 판매자 배송 설정을 조회한다.
 * 배송 설정은 상품 모듈이 소유하고, 배송 모듈은 이 인터페이스로만 읽는다.
 */
public interface SellerShippingPolicyProvider {

    /**
     * 판매자들의 배송 설정을 한 번에 조회한다.
     * 한 주문에 판매자가 여럿 섞이므로 판매자마다 따로 조회하지 않게 묶어서 받는다.
     * 설정을 등록하지 않은 판매자는 결과에 없다. 기본값은 호출하는 쪽이 정한다.
     *
     * @return 판매자 ID를 키로 한 배송 설정
     */
    Map<Long, SellerShippingPolicy> findBySellerIds(Collection<Long> sellerIds);
}
