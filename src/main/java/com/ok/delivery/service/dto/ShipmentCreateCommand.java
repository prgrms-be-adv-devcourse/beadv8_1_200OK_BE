package com.ok.delivery.service.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 완료된 주문으로 판매자별 shipment를 만드는 입력이다.
 *
 * @param paidAt  결제 완료 시각. 출고·도착 예정일 계산 기준
 * @param sellers 판매자별 배송비와 상품. 판매자 하나가 shipment 하나가 된다
 */
public record ShipmentCreateCommand(
        Long orderId,
        LocalDateTime paidAt,
        AddressCommand address,
        List<SellerShipmentCommand> sellers
) {
}
