package com.ok.carriersimulator.core;

import java.time.Instant;

/**
 * 송장에 남는 사건 하나.
 *
 * @param time     사건이 일어난 실제 시계의 시각. 재생은 빨리 감지만 시각은 가상 시각을 쓰지 않는다.
 *                 받는 쪽이 자기 시계와 비교해도 어긋나지 않게 하기 위해서다
 * @param location 장소 이름. 없으면 null
 */
public record TrackingEvent(StatusCode status, Instant time, String location, String description) {
}
