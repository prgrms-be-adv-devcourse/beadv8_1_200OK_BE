package com.ok.order.order.client;

import com.ok.common.exception.RestApiException;
import com.ok.order.order.client.dto.SellerItemAmountRequest;
import com.ok.order.order.client.dto.SellerShippingFeeResponse;
import com.ok.order.order.client.dto.ShippingFeesRequest;
import com.ok.order.order.client.dto.ShippingFeesResponse;
import com.ok.order.order.exception.OrderErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DeliveryApiClient {

    private final RestClient restClient;

    public DeliveryApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = InternalRestClient.create(baseUrl + "/internal/v1/delivery", OrderErrorCode.DELIVERY_API_FAILED);
    }

    // 판매자별 배송비와 도서산간 운임을 한 번에 조회 (판매자 ID -> 그 판매자 상품 금액 합계)
    // 따로 부르면 주문 한 건에 HTTP가 두 번이라, 부하가 몰릴 때 스레드·DB 커넥션을 그만큼 더 쓴다
    // TODO(#492): 배송의 실제 shipping-fees와 연결하는 테스트가 아직 없음. 주문 테스트는 Fake 클라이언트를 써서 요청·응답 형식이 어긋나도 잡히지 않는다
    public List<SellerShippingFeeResponse> getShippingFees(String postalCode, Map<Long, Long> itemAmountBySeller) {
        List<SellerItemAmountRequest> sellers = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : itemAmountBySeller.entrySet()) {
            sellers.add(new SellerItemAmountRequest(entry.getKey(), entry.getValue()));
        }

        ShippingFeesResponse response = restClient.post()
                .uri("/shipping-fees")
                .body(new ShippingFeesRequest(postalCode, sellers))
                .retrieve()
                .body(ShippingFeesResponse.class);

        if (response == null || response.sellers() == null) {
            throw new RestApiException(OrderErrorCode.DELIVERY_API_FAILED);
        }
        return response.sellers();
    }
}
