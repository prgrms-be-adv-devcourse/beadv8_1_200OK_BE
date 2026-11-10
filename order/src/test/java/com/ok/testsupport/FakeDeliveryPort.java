package com.ok.testsupport;

import com.ok.order.order.application.port.out.DeliveryPort;
import com.ok.order.order.domain.SellerShippingFee;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 배송 모듈 대신 메모리에 넣어 둔 판매자별 배송비를 돌려주는 Fake 출력 포트.
 * 등록 방식과 쓰는 이유는 {@link FakeProductPort}와 같다.
 */
@Component
@Primary
public class FakeDeliveryPort implements DeliveryPort {

    private final Map<Long, SellerShippingFee> feesBySeller = new ConcurrentHashMap<>();

    public void register(Long sellerId, SellerShippingFee fee) {
        feesBySeller.put(sellerId, fee);
    }

    public void clear() {
        feesBySeller.clear();
    }

    // 주문이 물어본 판매자 중 등록된 판매자만 돌려준다. 빠진 판매자를 주문이 막는지도 이것으로 확인할 수 있다
    @Override
    public Map<Long, SellerShippingFee> findShippingFees(String postalCode, Map<Long, Long> itemAmountBySeller) {
        Map<Long, SellerShippingFee> result = new LinkedHashMap<>();
        for (Long sellerId : itemAmountBySeller.keySet()) {
            SellerShippingFee fee = feesBySeller.get(sellerId);
            if (fee != null) {
                result.put(sellerId, fee);
            }
        }
        return result;
    }
}
