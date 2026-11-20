package com.ok.carriersimulator.core;

/**
 * 일부러 넣는 장애.
 *
 * @param responseDelayMillis GraphQL 응답을 늦추는 시간
 * @param errorPercent        GraphQL 요청 중 INTERNAL 오류로 답하는 비율(%)
 * @param callbackCopies      상태가 바뀔 때 같은 알림을 몇 건 보낼지. 1이면 중복이 없다
 * @param shuffleCallbacks    한 번에 꺼낸 알림의 순서를 섞을지
 */
public record FaultSettings(long responseDelayMillis, int errorPercent, int callbackCopies, boolean shuffleCallbacks) {

    public FaultSettings {
        if (responseDelayMillis < 0 || responseDelayMillis > 60_000) {
            throw new IllegalArgumentException("응답 지연은 0 이상 60,000밀리초 이하여야 합니다.");
        }
        if (errorPercent < 0 || errorPercent > 100) {
            throw new IllegalArgumentException("오류 비율은 0 이상 100 이하여야 합니다.");
        }
        if (callbackCopies < 1 || callbackCopies > 10) {
            throw new IllegalArgumentException("알림 중복 횟수는 1 이상 10 이하여야 합니다.");
        }
    }

    public static FaultSettings none() {
        return new FaultSettings(0, 0, 1, false);
    }
}
