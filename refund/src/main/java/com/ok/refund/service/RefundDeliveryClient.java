package com.ok.refund.service;

import com.ok.refund.service.dto.ShipmentReserveResult;

/**
 * 배송 모듈을 부른다. 1차 구현은 HTTP 동기 호출이고, 배송 팀 회신 전까지 경로·응답 모양은 요청서(설계 7장) 기준 가정이다.
 */
public interface RefundDeliveryClient {

    /**
     * 주문 상품의 배송 건이 있는지 확인한다. 승인할 때 "처리 중"으로 바꾸기 전에 부른다.
     * 배송 건은 한 번 생기면 사라지지 않으므로, 여기서 있다고 확인하면 뒤의 배송 잠금에서 "배송 건 없음"이 나지 않는다.
     *
     * @throws com.ok.common.exception.RestApiException 호출 실패면 {@code DELIVERY_API_FAILED}
     */
    boolean hasShipment(Long orderItemId);

    /**
     * 배송을 잠근다(취소 예약, 흐름 5번). 잠기면 판매자가 송장을 등록할 수 없다.
     *
     * @return 409면 {@link ShipmentReserveResult#REJECTED}
     * @throws com.ok.common.exception.RestApiException 그 밖의 실패면 {@code DELIVERY_API_FAILED}
     */
    ShipmentReserveResult reserveCancel(Long refundId, Long orderItemId, int quantity);

    /**
     * 배송 잠금을 푼다(취소 확정, 흐름 9번). 남은 상품은 다시 출고할 수 있고, 묶음 전부 취소면 배송이 취소된다.
     *
     * @throws com.ok.common.exception.RestApiException 호출 실패면 {@code DELIVERY_API_FAILED}
     */
    void confirmCancel(Long refundId);
}
