package com.ok.carriersimulator.core;

/**
 * 송장을 가리키는 키. delivery도 같은 택배사 안에서만 송장 번호 중복을 막으므로 택배사와 번호를 함께 쓴다.
 */
public record ParcelKey(String carrierId, String trackingNumber) {

    public ParcelKey {
        if (carrierId == null || carrierId.isBlank()) {
            throw new IllegalArgumentException("택배사는 필수입니다.");
        }
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new IllegalArgumentException("송장 번호는 필수입니다.");
        }
    }
}
