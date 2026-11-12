package com.ok.refund.adapter.out.product;

import com.ok.refund.adapter.out.order.InternalRestClient;
import com.ok.refund.adapter.out.product.dto.RefundStockPartialReleaseRequest;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.StockRelease;
import com.ok.refund.exception.RefundErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 상품 모듈 내부 API를 HTTP로 불러 환불한 개수만큼 재고를 되돌린다(#363 재고 일부 해제).
 * 이름에 Refund를 붙인 이유: 다른 모듈에도 상품 호출 클라이언트가 있어, monolith에서 빈 이름이 겹치면 앱이 뜨지 않는다.
 */
@Component
public class RefundProductHttpAdapter implements ProductStockReleasePort {

    private final RestClient restClient;

    // 주소 설정은 주문 어댑터와 같은 키·기본값을 쓴다. 한 앱(monolith)에서는 자기 자신을 부르게 된다
    public RefundProductHttpAdapter(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = InternalRestClient.create(baseUrl + "/internal/v1/products", RefundErrorCode.PRODUCT_API_FAILED);
    }

    // 상품 API: POST /internal/v1/products/stock-allocations/{orderId}/partial-release, 성공은 204(같은 refundId로 같은 내용이면 다시 204).
    // 실패(연결 실패·타임아웃·404·409·5xx)는 InternalRestClient가 모두 PRODUCT_API_FAILED로 바꾼다. 409(해제 가능 수량 초과,
    // 같은 refundId인데 내용이 다름)는 다시 시도해도 결과가 같지만 일시적 실패와 나누지 않는다. 성공 흐름만 만드는 범위라
    // 구분해도 다르게 처리할 길이 아직 없다(#516에서 구분)
    @Override
    public void releaseStock(StockRelease release) {
        restClient.post()
                .uri("/stock-allocations/{orderId}/partial-release", release.orderId())
                .body(RefundStockPartialReleaseRequest.from(release))
                .retrieve()
                .toBodilessEntity();
    }
}
