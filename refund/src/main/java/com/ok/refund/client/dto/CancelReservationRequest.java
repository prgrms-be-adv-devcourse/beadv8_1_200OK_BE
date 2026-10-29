package com.ok.refund.client.dto;

/**
 * 배송 모듈 {@code POST /internal/v1/delivery/cancel-reservations} 요청(요청서 7.3 기준 가정).
 */
public record CancelReservationRequest(Long refundId, Long orderItemId, int quantity) {
}
