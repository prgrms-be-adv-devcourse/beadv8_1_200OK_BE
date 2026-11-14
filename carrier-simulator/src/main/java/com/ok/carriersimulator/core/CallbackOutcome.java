package com.ok.carriersimulator.core;

/**
 * 알림을 보낸 결과.
 *
 * @param responseCode 받는 쪽의 HTTP 응답 코드. 응답을 받지 못했으면 null
 * @param failure      응답을 받지 못한 이유(시간 초과, 연결 실패 등). 응답을 받았으면 null
 */
public record CallbackOutcome(Integer responseCode, String failure, long elapsedMillis) {

    public static CallbackOutcome responded(int responseCode, long elapsedMillis) {
        return new CallbackOutcome(responseCode, null, elapsedMillis);
    }

    public static CallbackOutcome failed(String failure, long elapsedMillis) {
        return new CallbackOutcome(null, failure, elapsedMillis);
    }

    /**
     * Delivery Tracker처럼 2XX 응답만 성공으로 본다.
     */
    public boolean success() {
        return responseCode != null && responseCode >= 200 && responseCode < 300;
    }
}
