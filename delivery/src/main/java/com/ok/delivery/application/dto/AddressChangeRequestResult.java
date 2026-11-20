package com.ok.delivery.application.dto;

import com.ok.delivery.domain.ShipmentAddressChangeRequest;

import java.time.LocalDateTime;

/**
 * 배송지 변경 요청의 현재 상태다. 요청 생성과 승인·거절 결과로 쓴다.
 *
 * @param additionalFeeNotice 변경으로 늘어나는 추가 운임 안내 금액(원). 판매자가 구매자에게 직접 받는다
 * @param rejectReason        거절 사유. 거절되지 않았으면 {@code null}
 * @param processedAt         승인·거절 시각. 처리 전이면 {@code null}
 */
public record AddressChangeRequestResult(
        Long requestId,
        Long shipmentId,
        String status,
        int additionalFeeNotice,
        String rejectReason,
        LocalDateTime requestedAt,
        LocalDateTime processedAt
) {

    public static AddressChangeRequestResult from(ShipmentAddressChangeRequest request) {
        return new AddressChangeRequestResult(
                request.getId(),
                request.getShipmentId(),
                request.getStatus().name(),
                request.getAdditionalFeeNotice(),
                request.getRejectReason(),
                request.getRequestedAt(),
                request.getProcessedAt());
    }
}
