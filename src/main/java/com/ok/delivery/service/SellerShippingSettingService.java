package com.ok.delivery.service;

import com.ok.delivery.config.DefaultShippingProperties;
import com.ok.delivery.domain.SellerShippingSetting;
import com.ok.product.SellerShippingPolicy;
import com.ok.product.SellerShippingPolicyProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 배송비와 출고 예정일 계산에 쓸 판매자 배송 설정을 조회한다.
 * 설정은 상품 모듈이 소유하므로 {@link SellerShippingPolicyProvider}로 읽고, 설정이 없는 판매자에게는 기본값을 적용한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerShippingSettingService {

    private final SellerShippingPolicyProvider sellerShippingPolicyProvider;
    private final DefaultShippingProperties defaultShippingProperties;

    /**
     * 판매자들의 배송 설정을 조회한다.
     * 설정을 등록하지 않은 판매자도 빠짐없이 기본값으로 채워, 호출하는 쪽이 설정 유무를 따로 다루지 않게 한다.
     *
     * @return 요청한 모든 판매자 ID를 키로 한 배송 설정
     * @throws IllegalArgumentException 판매자 ID 목록이 없거나 null인 ID가 있으면, 또는 조회한 설정 값이 잘못되었으면
     */
    public Map<Long, SellerShippingSetting> getSettings(Collection<Long> sellerIds) {
        if (sellerIds == null || sellerIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("판매자 ID 목록은 필수이고 null인 ID를 포함할 수 없습니다.");
        }
        Set<Long> distinctSellerIds = new LinkedHashSet<>(sellerIds);
        Map<Long, SellerShippingPolicy> policies = sellerShippingPolicyProvider.findBySellerIds(distinctSellerIds);
        SellerShippingSetting defaultSetting = defaultShippingProperties.toSetting();

        Map<Long, SellerShippingSetting> settings = new LinkedHashMap<>();
        for (Long sellerId : distinctSellerIds) {
            SellerShippingPolicy policy = policies.get(sellerId);
            settings.put(sellerId, policy == null ? defaultSetting : toSetting(policy));
        }
        return settings;
    }

    private SellerShippingSetting toSetting(SellerShippingPolicy policy) {
        return new SellerShippingSetting(policy.baseShippingFee(), policy.freeShippingThreshold(), policy.cutoffTime());
    }
}
