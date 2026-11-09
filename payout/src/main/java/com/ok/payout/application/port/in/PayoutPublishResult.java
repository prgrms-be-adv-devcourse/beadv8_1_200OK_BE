package com.ok.payout.application.port.in;

// publishStep 결과 (#309). tasklet이 배치 실행 기록에 수를 남기는 데 씀
public record PayoutPublishResult(
        // Kafka로 보내고 "보냄"으로 표시한 줄 수
        int sent,
        // 보내다 실패해 PENDING으로 남긴 줄 수 (다음 실행에서 다시 보냄)
        int failed,
        // 연속 실패로 이번 발송을 멈춰 시도하지 않은 줄 수
        int remaining
) {
}
