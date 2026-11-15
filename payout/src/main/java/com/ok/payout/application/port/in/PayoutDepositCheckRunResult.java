package com.ok.payout.application.port.in;

// 매일 대사 한 번의 결과 (#619). 스케줄러가 로그로 남김
public record PayoutDepositCheckRunResult(
        // 3번을 다 물었는데 답이 끝내 오지 않아 이번에 관리자 확인 필요로 넘긴 줄 수
        int closedCount,
        // 이번에 지갑에 물은 정산 수 (Kafka로 보내기에 성공한 묶음만)
        int askedCount,
        // 이번에 보낸 질문 묶음 수
        int batchCount,
        // Kafka로 보내기에 실패해 남은 대기 줄을 묻지 못하고 멈췄는지
        boolean stoppedBySendFailure
) {
}
