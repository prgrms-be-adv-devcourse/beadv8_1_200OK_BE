package com.ok.delivery.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.regex.Pattern;

/**
 * 배송지 값 객체. 기본 배송지, 주문, shipment, 배송지 변경 요청이 같은 형태와 같은 검증 규칙을 쓴다.
 *
 * <p>{@link #of}로만 만들 수 있고, 생성 시점에 검증을 통과한 값만 존재한다. setter가 없어 만든 뒤에는 바뀌지 않는다.
 * 배송지를 바꾸려면 새 {@code Address}를 만들어 교체한다. 같은 값이면 같은 배송지로 본다.
 */
@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address {

    /** 숫자로 시작하고 숫자 사이에만 하이픈을 1개까지 허용, 숫자는 10~11자리 */
    private static final Pattern PHONE_INPUT = Pattern.compile("^\\d(?:-?\\d){9,10}$");
    private static final Pattern POSTAL_CODE = Pattern.compile("^\\d{5}$");

    @Column(name = "recipient_name", nullable = false, length = 50)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 11)
    private String recipientPhone;

    @Column(name = "postal_code", nullable = false, length = 5)
    private String postalCode;

    @Column(name = "address", nullable = false)
    private String address;

    @Column(name = "address_detail", nullable = false)
    private String addressDetail;

    @Column(name = "delivery_note")
    private String deliveryNote;

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

    /**
     * 배송지를 검증하고 만든다.
     *
     * <ul>
     *   <li>수령인, 연락처, 우편번호, 주소, 상세 주소는 필수다. 배송 요청사항({@code deliveryNote})만 선택이다.</li>
     *   <li>연락처는 하이픈을 허용해 입력받고, 하이픈을 뺀 숫자 10~11자리만 저장한다.</li>
     *   <li>우편번호는 숫자 5자리다.</li>
     * </ul>
     *
     * <p>요청 DTO가 먼저 같은 규칙으로 422를 낸다. 이 검증은 DTO를 거치지 않는 경로(이벤트, 배치)를 위한 마지막 방어선이다.
     *
     * @throws IllegalArgumentException 필수값이 없거나 연락처·우편번호 형식이 틀리면
     */
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

    /** 연락처 형식을 확인하고 하이픈을 뺀 숫자만 돌려준다. 예: {@code 010-1234-5678 → 01012345678} */
    private static String normalizePhone(String phone) {
        requireText(phone, "연락처는 필수입니다.");
        if (!PHONE_INPUT.matcher(phone).matches()) {
            throw new IllegalArgumentException("연락처는 숫자 10~11자리여야 합니다.");
        }
        return phone.replace("-", "");
    }

    /** 우편번호가 숫자 5자리인지 확인한다. 앞자리 0을 지키려고 문자열로 다룬다. */
    private static String validatePostalCode(String postalCode) {
        requireText(postalCode, "우편번호는 필수입니다.");
        if (!POSTAL_CODE.matcher(postalCode).matches()) {
            throw new IllegalArgumentException("우편번호는 숫자 5자리여야 합니다.");
        }
        return postalCode;
    }

    /** null, 빈 값, 공백만 있는 값을 거부한다. */
    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
