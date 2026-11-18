package com.ok.refund.adapter.out.product;

import com.ok.refund.adapter.out.order.InternalRestClient;
import com.ok.refund.adapter.out.product.dto.RefundStockPartialReleaseRequest;
import com.ok.refund.application.port.out.ProductStockReleasePort;
import com.ok.refund.application.port.out.StockRelease;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.common.exception.RestApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Set;

/**
 * 상품 모듈 내부 API를 HTTP로 불러 환불한 개수만큼 재고를 되돌린다(#363 재고 일부 해제).
 * 이름에 Refund를 붙인 이유: 다른 모듈에도 상품 호출 클라이언트가 있어, monolith에서 빈 이름이 겹치면 앱이 뜨지 않는다.
 */
@Slf4j
@Component
public class RefundProductHttpAdapter implements ProductStockReleasePort {

    // 다시 해도 결과가 같은 응답 상태. 상품 API 문서가 그렇게 적은 것은 409뿐이고, 404는 뜻으로 판단했다(아래 설명)
    private static final int REFUSED_STATUS = 409;
    private static final int NOT_FOUND_STATUS = 404;
    // 요청 값 오류. 우리가 보낸 값이 틀린 것이라 상품의 거절이 아니라 환불의 버그다(#546)
    private static final int INVALID_REQUEST_STATUS = 422;

    private final RestClient restClient;

    // 주소 설정은 주문 어댑터와 같은 키·기본값을 쓴다. 한 앱(monolith)에서는 자기 자신을 부르게 된다
    public RefundProductHttpAdapter(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = InternalRestClient.create(baseUrl + "/internal/v1/products", RefundErrorCode.PRODUCT_API_FAILED);
    }

    // 상품 API: POST /internal/v1/products/stock-allocations/{orderId}/partial-release, 성공은 204(같은 refundId로 같은 내용이면 다시 204).
    // 실패를 다시 시도할지로 나눈다(#576). 아래 둘은 다시 해도 결과가 같아 지휘자가 다시 시도하지 않고 단계를 멈춘다.
    //   409 해제 가능 수량 초과·같은 환불 다른 내용: 상품 API 문서(ProductInternalControllerSpec)가 "재시도해도 결과가 같음"으로 적었다.
    //       PRODUCT_RELEASE_REFUSED로 바꾸고, 지휘자는 이것만 보고 지갑 돈을 다시 가져온다(#546).
    //   404 배정 기록 없음: 문서에 다시 시도 여부는 없다. 없는 재고 기록은 다시 보내도 바뀌지 않는다고 판단했다.
    //       PRODUCT_STOCK_NOT_FOUND로 따로 둔다. 주소 설정 오류·게이트웨이 404와 가를 수 없어 돈을 다시 가져오지 않는다(#546 봇 리뷰, 사용자 결정).
    // 422 요청 값 오류는 우리가 보낸 값이 틀린 것이라 환불의 버그다(exception.md, 다른 모듈에 넘긴 값의 오류는 표준 예외).
    // 상품의 거절(PRODUCT_RELEASE_REFUSED)로 섞으면 지갑 돈 다시 가져오기(#546)가 우리 버그 때문에 구매자 돈을 회수하게 되므로 따로 둔다.
    // 표준 예외라 지휘자는 이유를 예외 클래스 이름으로 남기고 다시 시도한 뒤 멈춘다(바로 멈추려면 422 전용 이유 값이 필요해 두지 않았다, #546 결정).
    // 상품은 422를 받으면 아무것도 바꾸지 않아 다시 보내도 안전하고, 사람에게는 1시간마다 도는 멈춘 환불 찾기가 알린다.
    // DB의 이유 칸에는 클래스 이름만 남으므로 "상품 422"는 예외 메시지로 남겨 ERROR 로그에서 구분한다
    // 그 밖의 4xx(401·403 내부 호출 막힘 같은 설정 문제, 408 요청 시간 초과, 429 요청 과다)와 연결 실패·타임아웃·5xx는
    // 고치면 풀리거나 잠깐의 실패일 수 있어 InternalRestClient가 바꾸는 PRODUCT_API_FAILED 그대로 두고 알람이 다시 시도한다.
    // 이유(type)는 가르지 않는다. type은 PROBLEM_BASE_URI 설정이 기본값이면 about:blank라 믿을 수 없고, 404·409 안에서는 처리가 같다
    @Override
    public void releaseStock(StockRelease release) {
        try {
            restClient.post()
                    .uri("/stock-allocations/{orderId}/partial-release", release.orderId())
                    .body(RefundStockPartialReleaseRequest.from(release))
                    .retrieve()
                    // 메서드의 onStatus가 InternalRestClient의 기본 상태 처리보다 먼저 적용된다
                    .onStatus(status -> status.value() == REFUSED_STATUS, (request, response) -> {
                        throw new RestApiException(RefundErrorCode.PRODUCT_RELEASE_REFUSED);
                    })
                    .onStatus(status -> status.value() == NOT_FOUND_STATUS, (request, response) -> {
                        throw new RestApiException(RefundErrorCode.PRODUCT_STOCK_NOT_FOUND);
                    })
                    .onStatus(status -> status.value() == INVALID_REQUEST_STATUS, (request, response) -> {
                        throw new IllegalStateException("상품이 재고 해제 요청 값을 거절함(422, 환불이 보낸 값의 오류). refundId="
                                + release.refundId() + ", orderId=" + release.orderId());
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            // 응답 상태줄은 InternalRestClient 인터셉터(요청을 보내는 순간만 감쌈) 밖에서 읽는다. 그래서 응답을 읽다 끊기거나
            // 3초가 지나면 인터셉터 대신 ResourceAccessException으로 올라와 500이 된다(#578). 주문 취소 반영과 같은 코드로 바꿔 503으로 맞춘다.
            // onStatus가 던진 예외는 이 예외가 아니라 그대로 올라간다.
            // RestApiException은 원인을 담지 못해, 여기서 버리면 시간 초과인지 끊김인지 알 수 없다. 그래서 바꾸기 전에 원인을 남긴다.
            // 상품이 잠깐 안 되는 것이라 WARN이다(다시 시도는 알람이 한다)
            log.warn("상품 재고 복구 응답을 읽지 못함(끊김·시간 초과). refundId={}, orderId={}", release.refundId(), release.orderId(), e);
            throw new RestApiException(RefundErrorCode.PRODUCT_API_FAILED);
        }
    }
}
