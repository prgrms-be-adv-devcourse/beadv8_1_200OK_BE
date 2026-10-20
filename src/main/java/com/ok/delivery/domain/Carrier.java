package com.ok.delivery.domain;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.exception.DeliveryErrorCode;

import java.util.Arrays;

/**
 * 송장을 발급하는 택배사. 저장 값은 이름 그대로({@code shipment.carrier})다.
 */
public enum Carrier {
    CJ_LOGISTICS,
    POST_OFFICE,
    HANJIN,
    LOTTE,
    LOGEN;

    /**
     * 판매자가 입력한 택배사 코드를 찾는다.
     * 요청 DTO도 같은 값을 검사하지만, 다른 경로로 들어온 사용자 입력도 500이 아니라 입력 오류로 응답하게 한다.
     *
     * @throws RestApiException 비어 있으면 {@code MISSING_CARRIER}, 없는 코드면 {@code INVALID_CARRIER}
     */
    public static Carrier from(String code) {
        if (code == null || code.isBlank()) {
            throw new RestApiException(DeliveryErrorCode.MISSING_CARRIER);
        }
        return Arrays.stream(values())
                .filter(carrier -> carrier.name().equals(code))
                .findFirst()
                .orElseThrow(() -> new RestApiException(DeliveryErrorCode.INVALID_CARRIER));
    }
}
