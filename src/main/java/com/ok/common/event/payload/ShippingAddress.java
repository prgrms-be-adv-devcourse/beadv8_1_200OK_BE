package com.ok.common.event.payload;

import com.ok.common.event.OrderPaidEvent;

/**
 * 결제 시점에 확정된 배송지를 담는다. {@link OrderPaidEvent}의 일부이며 배송의 {@code Address}와 같은 필드다.
 * {@code common}은 배송 모듈을 import할 수 없어 따로 둔다. 형식 검증(연락처, 우편번호)은 배송이 {@code Address}로 바꿀 때 한다.
 *
 * @param recipientName  수령인 이름
 * @param recipientPhone 수령인 연락처. 하이픈이 있어도 된다
 * @param postalCode     우편번호
 * @param address        주소
 * @param addressDetail  상세 주소
 * @param deliveryNote   배송 요청사항. 없으면 {@code null}
 */
public record ShippingAddress(
        String recipientName,
        String recipientPhone,
        String postalCode,
        String address,
        String addressDetail,
        String deliveryNote
) {

    public ShippingAddress {
        requireText(recipientName, "recipientName");
        requireText(recipientPhone, "recipientPhone");
        requireText(postalCode, "postalCode");
        requireText(address, "address");
        requireText(addressDetail, "addressDetail");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "는 필수입니다.");
        }
    }
}
