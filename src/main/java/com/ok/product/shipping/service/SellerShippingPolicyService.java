package com.ok.product.shipping.service;

import com.ok.product.SellerShippingPolicy;
import com.ok.product.SellerShippingPolicyProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;

/**
 * 판매자 배송 설정을 조회한다.
 * 판매자 배송 설정 저장소가 생기기 전까지는 모든 판매자를 "설정 없음"으로 돌려줘, 배송 모듈이 기본값을 적용하게 한다.
 */
@Service
@Transactional(readOnly = true)
public class SellerShippingPolicyService implements SellerShippingPolicyProvider {

    // TODO(논의 필요): 판매자 배송 설정의 저장 위치와 등록 API를 상품 팀과 정한 뒤 실제 조회로 바꾼다
    @Override
    public Map<Long, SellerShippingPolicy> findBySellerIds(Collection<Long> sellerIds) {
        if (sellerIds == null) {
            throw new IllegalArgumentException("판매자 ID 목록은 필수입니다.");
        }
        return Map.of();
    }
}
