package com.ok.payout.adapter.in.batch;

import com.ok.payout.application.port.in.PayoutBlockedOrderUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

// 정산 Job이 끝날 때 막힌 주문 집계 표(#582)를 갱신함
// COMPLETED·FAILED 모두 갱신: FAILED로 끝난 실행에서 남은 실패 기록도 주문을 잠그기 때문 (잠금은 실행 결과를 보지 않음)
// afterJob을 쓰는 이유: Spring Batch(6.0)는 실행 상태를 DB에 저장한 뒤 afterJob을 부름("save status in job repository before calling listeners")
//   → 실패 횟수 SQL(COMPLETED 실행만 셈)이 방금 끝난 실행까지 셈. 마지막 Step으로 두면 그때는 아직 실행 중이라 이번 실행이 빠짐
// afterJob은 Job 시작기(PayoutJobRunner)의 ShedLock 안에서 돌아, 다른 Job의 갱신과 겹치지 않음
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutBlockedOrderRefreshListener implements JobExecutionListener {

    private final PayoutBlockedOrderUseCase payoutBlockedOrderUseCase;

    // 갱신이 실패해도 정산 결과(Job 상태)는 바꾸지 않음: 표는 보기용이라 돈이 나가는 일과 상관없음
    //   반영 표시가 그대로라 다음 Job이 끝날 때 놓친 범위까지 다시 판정함
    // ERROR로 남기는 이유: 갱신 코드나 조회가 틀린 시스템 문제라 개발자가 알아야 함 (payout/CLAUDE.md "[정산-시스템문제]")
    @Override
    public void afterJob(JobExecution jobExecution) {
        try {
            payoutBlockedOrderUseCase.refresh();
        } catch (RuntimeException e) {
            log.error("[정산-시스템문제] 막힌 주문 표 갱신 실패. 다음 Job 끝에 다시 판정함. jobExecutionId={}",
                    jobExecution.getId(), e);
        }
    }
}
