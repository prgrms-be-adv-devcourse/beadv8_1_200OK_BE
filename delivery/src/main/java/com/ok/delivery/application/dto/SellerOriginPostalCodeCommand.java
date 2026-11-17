package com.ok.delivery.application.dto;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 판매자 출고지 우편번호를 저장하는 입력이다.
 *
 * @param postalCode 출고지 우편번호(숫자 5자리). 없으면 출고지를 비운다
 * @param occurredAt member에서 값이 바뀐 시각. 이보다 새로운 값이 이미 있으면 이 입력은 버려진다
 */
public record SellerOriginPostalCodeCommand(Long sellerId, String postalCode, LocalDateTime occurredAt) {

    private static final Pattern POSTAL_CODE = Pattern.compile("^\\d{5}$");

    /**
     * 다른 모듈이 보낸 값이라 잘못된 값은 호출한 쪽의 오류로 보고 표준 예외를 던진다.
     *
     * @throws IllegalArgumentException 판매자 ID나 발생 시각이 없거나, 우편번호가 숫자 5자리가 아니면
     */
    public SellerOriginPostalCodeCommand {
        if (sellerId == null) {
            throw new IllegalArgumentException("판매자 ID는 필수입니다.");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("메시지 발생 시각은 필수입니다.");
        }
        if (postalCode != null && !POSTAL_CODE.matcher(postalCode).matches()) {
            throw new IllegalArgumentException("우편번호는 숫자 5자리여야 합니다.");
        }
    }
}
