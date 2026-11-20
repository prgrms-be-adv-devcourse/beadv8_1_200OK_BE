package com.ok.delivery.application.dto;

import com.ok.delivery.domain.Address;

public record AddressCommand(
        String recipientName,
        String recipientPhone,
        String postalCode,
        String address,
        String addressDetail,
        String deliveryNote
) {

    /**
     * 배송지 값 객체로 바꾼다.
     * 컨트롤러가 도메인 타입을 알지 않도록 변환은 서비스 계층에서 한다.
     *
     * @throws com.ok.common.exception.RestApiException 검증 규칙은 {@link Address#of}를 따른다
     */
    public Address toAddress() {
        return Address.of(
                recipientName,
                recipientPhone,
                postalCode,
                address,
                addressDetail,
                deliveryNote
        );
    }
}
