package com.ok.testsupport;

import com.ok.order.order.client.DeliveryApiClient;
import com.ok.order.order.client.dto.SellerShippingFeeResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 배송 모듈 배송비 조회 내부 API 대신 메모리에 넣어 둔 판매자별 배송비를 돌려주는 Fake.
 * 등록 방식과 쓰는 이유는 {@link FakeProductApiClient}와 같다.
 */
@Component
@Primary
public class FakeDeliveryApiClient extends DeliveryApiClient {

    private final Map<Long, SellerShippingFeeResponse> feesBySeller = new ConcurrentHashMap<>();

    public FakeDeliveryApiClient() {
        super("http://localhost:0"); // 실제로 호출하지 않으므로 주소는 의미 없다
    }

    public void register(SellerShippingFeeResponse fee) {
        feesBySeller.put(fee.sellerId(), fee);
    }

    public void clear() {
        feesBySeller.clear();
    }

    // 주문이 물어본 판매자 중 등록된 판매자만 돌려준다. 빠진 판매자를 주문이 막는지도 이것으로 확인할 수 있다
    @Override
    public List<SellerShippingFeeResponse> getShippingFees(String postalCode, Map<Long, Long> itemAmountBySeller) {
        return itemAmountBySeller.keySet().stream()
                .map(feesBySeller::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
