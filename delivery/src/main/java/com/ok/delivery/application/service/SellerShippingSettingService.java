package com.ok.delivery.application.service;

import com.ok.delivery.application.port.out.SellerShippingProfileRepository;
import com.ok.delivery.config.DefaultShippingProperties;
import com.ok.delivery.domain.SellerShippingProfile;
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
import java.util.stream.Collectors;

/**
 * 배송비와 출고 예정일 계산에 쓸 판매자 배송 설정을 조회한다.
 * 설정은 배송 모듈이 소유하며, 설정을 저장하지 않은 판매자에게는 기본값을 적용한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerShippingSettingService {

    private final SellerShippingProfileRepository sellerShippingProfileRepository;
    private final DefaultShippingProperties defaultShippingProperties;

    /**
     * 판매자들의 배송 설정을 조회한다.
     * 저장한 설정이 없는 판매자는 기본값으로 채워, 호출하는 쪽이 설정 유무를 따로 다루지 않게 한다.
     *
     * @return 요청한 모든 판매자 ID를 키로 한 배송 설정
     * @throws IllegalArgumentException 판매자 ID 목록이 없거나 null인 ID가 있으면,
     *                                  조회한 설정 값이 잘못되었으면
     */
    public Map<Long, SellerShippingSetting> getSettings(Collection<Long> sellerIds) {
        if (sellerIds == null || sellerIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("판매자 ID 목록은 필수이고 null인 ID를 포함할 수 없습니다.");
        }
        Set<Long> distinctSellerIds = new LinkedHashSet<>(sellerIds);
        if (distinctSellerIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<Long, SellerShippingSetting> storedSettings = sellerShippingProfileRepository
                .findAllBySellerIdIn(distinctSellerIds).stream()
                .collect(Collectors.toMap(SellerShippingProfile::getSellerId, SellerShippingProfile::toSetting));
        SellerShippingSetting defaultSetting = defaultShippingProperties.toSetting();

        // TODO(논의 필요): 판매자 배송 설정 등록·수정 API(배송 모듈)를 정한다
        Map<Long, SellerShippingSetting> settings = new LinkedHashMap<>();
        for (Long sellerId : distinctSellerIds) {
            settings.put(sellerId, storedSettings.getOrDefault(sellerId, defaultSetting));
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
