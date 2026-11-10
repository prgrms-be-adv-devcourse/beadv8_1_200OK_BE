package com.ok.payout.application.port.in;

/**
 * outbox 발송 한 회차의 결과를 담는다 (#309).
 * 발행 tasklet이 배치 실행 기록에 보낸 수·실패 수를 남기는 데 쓴다.
 */
public record PayoutPublishResult(
        // Kafka로 보내고 "보냄"으로 표시한 줄 수
        int sent,
        // Kafka 발송이나 보냄 기록에 실패해 PENDING으로 남긴 줄 수 (다음 실행에서 다시 보냄)
        int failed,
        // 연속 실패나 중단으로 이번 발송을 멈춰 시도하지 않은 줄 수
        int remaining
) {
}
