package com.ok.delivery.application.dto;

/**
 * 구매자의 배송지 변경 요청 입력이다.
 *
 * @param buyerId          요청하는 구매자 회원 ID. 본인 shipment인지 확인한다
 * @param requestedAddress 바꿀 배송지
 */
public record AddressChangeRequestCommand(
        Long shipmentId,
        Long buyerId,
        AddressCommand requestedAddress
) {
}
