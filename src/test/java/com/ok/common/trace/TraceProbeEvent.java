package com.ok.common.trace;

// 통합 테스트 전용 이벤트. 리스너 스레드에서 보이는 traceId를 관찰하기 위해 쓴다
public record TraceProbeEvent(String key) {
}
