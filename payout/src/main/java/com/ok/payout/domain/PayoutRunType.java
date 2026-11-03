package com.ok.payout.domain;

// 정산 Job이 어느 경로로 실행됐는지 (#389)
// 스냅샷(payout_snapshot.run_type)과 Job 파라미터(runType)에 함께 남김
// 배치 실행 기록(BATCH_*)은 정리(삭제)될 수 있어, 정산 데이터 쪽에도 오래 남도록 스냅샷에 저장함
// 다음 정산의 조회 하한을 "마지막으로 완료된 정기 실행" 기준으로 바꿀 때(#390) 정기 실행만 골라내는 데 씀
public enum PayoutRunType {

    // 매일 새벽 2시 스케줄러 (PayoutJobScheduler)
    SCHEDULED,

    // 관리자 정산 실행 API (/api/v1/admin/payouts/run)
    MANUAL,

    // 대기 건너뛰기 실행 API (/api/v1/admin/payouts/run/skip-wait, #224). 15일 대기를 건너뛰어 정산함
    SKIP_WAIT
}
