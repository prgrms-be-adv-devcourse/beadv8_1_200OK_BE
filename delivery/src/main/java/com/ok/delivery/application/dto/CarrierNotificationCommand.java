package com.ok.delivery.application.dto;

/**
 * 택배사가 배송 상태가 바뀌었다고 알려 온 송장이다. 상태 자체는 담기지 않아 받은 쪽이 택배사에 다시 조회한다.
 *
 * @param carrierId      택배사가 쓰는 식별자(예: {@code kr.cjlogistics}). 우리 택배사 코드가 아니다.
 * @param trackingNumber 송장번호
 */
public record CarrierNotificationCommand(String carrierId, String trackingNumber) {
}
