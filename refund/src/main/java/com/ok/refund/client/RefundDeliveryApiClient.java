package com.ok.refund.client;

import com.ok.refund.client.dto.CancelReservationRequest;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.service.RefundDeliveryClient;
import com.ok.refund.service.dto.ShipmentReserveResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 배송 모듈 내부 API를 HTTP로 부른다. 경로와 응답 모양은 배송 팀 회신 전까지 요청서(설계 7장) 기준 가정이다.
 * 이름에 Refund를 붙인 이유: 주문 모듈에 {@code DeliveryApiClient} 빈이 있어 monolith에서 빈 이름이 겹치면 앱이 뜨지 않는다.
 */
@Slf4j
@Component
public class RefundDeliveryApiClient implements RefundDeliveryClient {

    private final RestClient restClient;

    public RefundDeliveryApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
        this.restClient = InternalRestClient.create(baseUrl + "/internal/v1/delivery", RefundErrorCode.DELIVERY_API_FAILED);
    }

    @Override
    public boolean hasShipment(Long orderItemId) {
        ResponseEntity<Void> response = restClient.get()
                .uri("/order-items/{orderItemId}/shipment", orderItemId)
                .retrieve()
                // 404는 "아직 배송 건 없음"이라는 정상 답이다. 오류로 바꾸지 않고 상태 코드로 판단한다
                .onStatus(status -> status.value() == 404, (request, httpResponse) -> {
                })
                .toBodilessEntity();
        return response.getStatusCode().is2xxSuccessful();
    }

    @Override
    public ShipmentReserveResult reserveCancel(Long refundId, Long orderItemId, int quantity) {
        ResponseEntity<Void> response = restClient.post()
                .uri("/cancel-reservations")
                .body(new CancelReservationRequest(refundId, orderItemId, quantity))
                .retrieve()
                // 이미 출고·수량 초과 등. 오류 응답에 코드 칸이 없어 이유는 나누지 않는다(설계 4.3)
                .onStatus(status -> status.value() == 409, (request, httpResponse) -> {
                })
                .toBodilessEntity();
        if (response.getStatusCode().value() == 409) {
            log.info("배송 잠금 거절, 승인 불가로 처리. refundId={}, orderItemId={}", refundId, orderItemId);
            return ShipmentReserveResult.REJECTED;
        }
        return ShipmentReserveResult.RESERVED;
    }

    @Override
    public void confirmCancel(Long refundId) {
        restClient.post()
                .uri("/cancel-reservations/{refundId}/confirm", refundId)
                .retrieve()
                .toBodilessEntity();
    }
}
