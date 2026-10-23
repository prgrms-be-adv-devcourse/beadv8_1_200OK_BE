package com.ok.delivery.service;

import com.ok.delivery.config.DefaultShippingProperties;
import com.ok.delivery.domain.SellerShippingSetting;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 배송비와 출고 예정일 계산에 쓸 판매자 배송 설정을 조회한다.
 * 설정은 배송 모듈이 소유하며, 저장소가 생기기 전까지 모든 판매자에게 기본값을 적용한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerShippingSettingService {

    private final DefaultShippingProperties defaultShippingProperties;

    /**
     * 판매자들의 배송 설정을 조회한다.
     * 모든 판매자를 빠짐없이 기본값으로 채워, 호출하는 쪽이 설정 유무를 따로 다루지 않게 한다.
     *
     * @return 요청한 모든 판매자 ID를 키로 한 배송 설정
     * @throws IllegalArgumentException 판매자 ID 목록이 없거나 null인 ID가 있으면
     */
    public Map<Long, SellerShippingSetting> getSettings(Collection<Long> sellerIds) {
        if (sellerIds == null || sellerIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("판매자 ID 목록은 필수이고 null인 ID를 포함할 수 없습니다.");
        }
        Set<Long> distinctSellerIds = new LinkedHashSet<>(sellerIds);
        SellerShippingSetting defaultSetting = defaultShippingProperties.toSetting();

        // TODO(논의 필요): 판매자 배송 설정 테이블과 판매자 등록·수정 API(배송 모듈)를 정한 뒤 저장된 설정 조회로 바꾸고, 없는 판매자만 기본값을 쓴다
        Map<Long, SellerShippingSetting> settings = new LinkedHashMap<>();
        for (Long sellerId : distinctSellerIds) {
            settings.put(sellerId, defaultSetting);
        }
        return settings;
    }

    /**
     * 판매자 한 명의 배송 설정을 조회한다. 설정을 등록하지 않았으면 기본값이다.
     *
     * @throws IllegalArgumentException 판매자 ID가 없으면
     */
    public SellerShippingSetting getSetting(Long sellerId) {
        if (sellerId == null) {
            throw new IllegalArgumentException("판매자 ID는 필수입니다.");
        }
        return getSettings(List.of(sellerId)).get(sellerId);
    }
}
