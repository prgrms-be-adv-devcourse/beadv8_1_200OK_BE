package com.ok.delivery.application.dto;

import com.ok.delivery.domain.Address;

/**
 * 배송지 값이다.
 *
 * @param recipientPhone 수령인 연락처. 하이픈 없이 숫자만 담는다
 * @param deliveryNote   배송 요청사항. 없으면 {@code null}
 */
public record AddressResult(
        String recipientName,
        String recipientPhone,
        String postalCode,
        String address,
        String addressDetail,
        String deliveryNote
) {

    public static AddressResult from(Address address) {
        return new AddressResult(
                address.getRecipientName(),
                address.getRecipientPhone(),
                address.getPostalCode(),
                address.getAddress(),
                address.getAddressDetail(),
                address.getDeliveryNote());
    }
}
