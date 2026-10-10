package com.ok.delivery.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.regex.Pattern;

@Getter
@EqualsAndHashCode
public class Address {

    private static final Pattern PHONE_INPUT = Pattern.compile("^\\d(?:-?\\d){9,10}$");
    private static final Pattern POSTAL_CODE = Pattern.compile("^\\d{5}$");

    private final String recipientName;
    private final String recipientPhone;
    private final String postalCode;
    private final String address;
    private final String addressDetail;
    private final String deliveryNote;

    private Address(
            String recipientName,
            String recipientPhone,
            String postalCode,
            String address,
            String addressDetail,
            String deliveryNote
    ) {
        this.recipientName = requireText(recipientName, "수령인은 필수입니다.");
        this.recipientPhone = normalizePhone(recipientPhone);
        this.postalCode = validatePostalCode(postalCode);
        this.address = requireText(address, "주소는 필수입니다.");
        this.addressDetail = requireText(addressDetail, "상세 주소는 필수입니다.");
        this.deliveryNote = deliveryNote;
    }

    public static Address of(
            String recipientName,
            String recipientPhone,
            String postalCode,
            String address,
            String addressDetail,
            String deliveryNote
    ) {
        return new Address(recipientName, recipientPhone, postalCode, address, addressDetail, deliveryNote);
    }

    private static String normalizePhone(String phone) {
        requireText(phone, "연락처는 필수입니다.");
        if (!PHONE_INPUT.matcher(phone).matches()) {
            throw new IllegalArgumentException("연락처는 숫자 10~11자리여야 합니다.");
        }
        return phone.replace("-", "");
    }

    private static String validatePostalCode(String postalCode) {
        requireText(postalCode, "우편번호는 필수입니다.");
        if (!POSTAL_CODE.matcher(postalCode).matches()) {
            throw new IllegalArgumentException("우편번호는 숫자 5자리여야 합니다.");
        }
        return postalCode;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
