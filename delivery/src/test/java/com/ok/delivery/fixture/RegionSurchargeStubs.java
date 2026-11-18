package com.ok.delivery.fixture;

import com.ok.delivery.application.dto.RegionResult;
import com.ok.delivery.application.service.DeliveryRegionService;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * mock {@link DeliveryRegionService}의 판매자별 추가 운임 판정을 채운다.
 * 출고지에 따른 면제는 {@code DeliveryRegionServiceTest}가 검증하므로, 다른 서비스 테스트는 면제가 없는 기본 동작을 쓴다.
 */
public final class RegionSurchargeStubs {

    private RegionSurchargeStubs() {
    }

    /**
     * 모든 판매자가 배송지 지역의 추가 운임을 그대로 내도록 한다.
     * 추가 운임을 판정하지 않는 테스트에서도 실패하지 않도록 lenient로 건다.
     */
    public static void chargeDestinationSurcharge(DeliveryRegionService deliveryRegionService) {
        lenient().when(deliveryRegionService.resolveSurcharges(any(), any())).thenAnswer(invocation -> {
            RegionResult destination = invocation.getArgument(0);
            Collection<Long> sellerIds = invocation.getArgument(1);
            Map<Long, Integer> surcharges = new LinkedHashMap<>();
            sellerIds.forEach(sellerId -> surcharges.put(sellerId, destination.surcharge()));
            return surcharges;
        });
    }
}
