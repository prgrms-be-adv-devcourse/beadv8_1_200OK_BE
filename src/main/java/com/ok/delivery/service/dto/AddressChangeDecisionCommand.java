package com.ok.delivery.service.dto;

/**
 * 판매자의 배송지 변경 요청 승인·거절 입력이다.
 *
 * @param sellerId 처리하는 판매자 회원 ID. 본인 shipment인지 확인한다
 */
public record AddressChangeDecisionCommand(
        Long shipmentId,
        Long requestId,
        Long sellerId,
        AddressChangeDecision decision
) {
}
