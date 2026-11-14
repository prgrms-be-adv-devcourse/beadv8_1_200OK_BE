package com.ok.carriersimulator.core;

/**
 * Delivery Tracker의 {@code TrackEventStatusCode}와 같은 값이다. 이름을 바꾸면 응답 형식이 달라진다.
 */
public enum StatusCode {
    UNKNOWN("알 수 없음"),
    INFORMATION_RECEIVED("접수"),
    AT_PICKUP("집화"),
    IN_TRANSIT("이동 중"),
    OUT_FOR_DELIVERY("배송 출발"),
    ATTEMPT_FAIL("배송 실패"),
    DELIVERED("배송 완료"),
    AVAILABLE_FOR_PICKUP("수령 대기"),
    EXCEPTION("사고");

    private final String displayName;

    StatusCode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
