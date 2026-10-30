package com.ok.delivery.application.port.in;

import com.ok.delivery.application.dto.AddressChangeDecisionCommand;
import com.ok.delivery.application.dto.AddressChangeRequestCommand;
import com.ok.delivery.application.dto.AddressChangeRequestResult;

/**
 * 배송지 변경 요청과 판매자의 승인·거절 유스케이스.
 */
public interface ShipmentAddressChangeUseCase {

    /**
     * 구매자가 배송지 변경을 요청한다.
     * shipment 행 잠금 아래에서 대기 중인 요청을 확인하므로, 동시에 요청해도 {@code PENDING}은 shipment당 1건만 생긴다.
     * 추가 운임 안내 금액은 요청 배송지의 지역을 판정해 {@link com.ok.delivery.domain.Shipment#requestAddressChange}가 계산한다.
     *
     * @throws com.ok.common.exception.RestApiException shipment가 없거나 다른 구매자의 shipment면 {@code SHIPMENT_NOT_FOUND},
     *                          대기 중인 요청이 있으면 {@code ADDRESS_CHANGE_ALREADY_PENDING},
     *                          {@code PREPARING}이 아니면 {@code ADDRESS_CHANGE_NOT_ALLOWED},
     *                          배송지 검증에 실패하면 {@link com.ok.delivery.domain.Address#of}의 에러 코드
     */
    AddressChangeRequestResult request(AddressChangeRequestCommand command);

    /**
     * 판매자가 배송지 변경 요청을 승인하거나 거절한다.
     * 승인하면 shipment의 배송지, 지역 그룹, 추가 운임 기준, 도착 예정일을 요청 배송지 기준으로 바꾼다.
     * 도착 예정일은 아직 출고 전이므로 승인 시각과 판매자 마감 시간으로 출고 예정일부터 다시 계산한다.
     *
     * @throws com.ok.common.exception.RestApiException shipment가 없거나 다른 판매자의 shipment면 {@code SHIPMENT_NOT_FOUND},
     *                          이 shipment의 요청이 아니면 {@code ADDRESS_CHANGE_REQUEST_NOT_FOUND},
     *                          이미 처리된 요청이면 {@code ADDRESS_CHANGE_ALREADY_PROCESSED},
     *                          승인할 때 {@code PREPARING}이 아니면 {@code INVALID_SHIPMENT_STATE}
     */
    AddressChangeRequestResult decide(AddressChangeDecisionCommand command);
}
