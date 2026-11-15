package com.ok.carriersimulator.core;

/**
 * GraphQL 요청을 받아들일지에 대한 판단.
 *
 * @param delayMillis 응답하기 전에 기다릴 시간. 거절할 때도 기다린다
 */
public record Admission(long delayMillis, Rejection rejection) {

    public boolean rejected() {
        return rejection != Rejection.NONE;
    }

    public enum Rejection {
        NONE,
        UNAUTHENTICATED,
        INTERNAL
    }
}
