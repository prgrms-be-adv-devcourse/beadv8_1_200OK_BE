package com.ok.delivery.application.dto;

import com.ok.delivery.domain.ShipmentAddressChangeRequest;

import java.time.LocalDateTime;

/**
 * 가장 최근 배송지 변경 요청이다. 대기 중이거나 처리된 요청 모두 담는다.
 *
 * @param additionalFeeNotice 이 요청으로 늘어나는 추가 운임 안내 금액(원)
 * @param rejectReason        거절 사유. 거절되지 않았으면 {@code null}
 * @param processedAt         승인·거절 시각. 처리 전이면 {@code null}
 */
public record LatestAddressChangeResult(
        Long requestId,
        String status,
        AddressResult requestedAddress,
        int additionalFeeNotice,
        String rejectReason,
        LocalDateTime requestedAt,
        LocalDateTime processedAt
) {

    public static LatestAddressChangeResult from(ShipmentAddressChangeRequest request) {
        return new LatestAddressChangeResult(
                request.getId(),
                request.getStatus().name(),
                AddressResult.from(request.getRequestedAddress()),
                request.getAdditionalFeeNotice(),
                request.getRejectReason(),
                request.getRequestedAt(),
                request.getProcessedAt());
    }
}
