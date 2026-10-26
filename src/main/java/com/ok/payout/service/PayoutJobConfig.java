package com.ok.payout.service;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.payout.service.dto.PayoutDraftResult;
import com.ok.payout.service.dto.PayoutVerifyResult;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;

@Configuration
@RequiredArgsConstructor
public class PayoutJobConfig {

    // 다음 Step들이 스냅샷 ID를 꺼내 쓰는 키 (JobExecutionContext)
    public static final String SNAPSHOT_ID_KEY = "snapshotId";

    private final PayoutService payoutService;

    // 정산 배치 (Write-Audit-Publish): 스냅샷 → 입력 검증 → 임시 정산 → 검증 → 확정 → 발행
    //   snapshot : 이번 정산 대상을 스냅샷 목록으로 고정 (단일 트랜잭션, 잠긴 주문의 후보 제외)
    //   validate : 지갑 차감 기록이 없거나 금액이 맞지 않는 주문의 후보를 실패로 기록 (이번 정산에서 빠짐, Job은 계속 진행)
    //              금액이 맞지 않는 주문은 잠겨 다음 배치부터 snapshot에서 빠짐
    //   draft    : 판매자 N명 단위 회차 반복으로 임시 정산 생성 (실패로 기록된 후보 제외)
    //              판매자마다 트랜잭션을 따로 둬서, 한 판매자에서 예외가 나면 그 판매자만 실패로 기록하고 계속 진행 (#90)
    //              단 전원 실패(성공 0명)면 여기서 멈춤 (지킬 정산이 없는 공통 오류를 FAILED로 드러냄)
    //   verify   : 스냅샷과 임시 정산을 후보 단위로 비교, 어긋난 후보의 주문만 실패로 기록하고 잠금 (#290)
    //              단 코드 자체가 망가진 경우(판매자 합계 불일치 등)와 정산할 것이 0건 남은 경우는 여기서 멈춤
    //   commit   : 임시 정산을 payout·payout_item으로 확정 (단일 트랜잭션, 분리 금지). 결과 검증에 걸린 주문의 항목은 뺌
    //   publish  : 이번에 확정된 판매자 몫 정산만 지갑 이벤트 발행
    // 앞 Step이 실패하면 Job이 FAILED로 멈추고 뒤 Step은 실행되지 않음
    // 실패한 Job은 재시작하지 않고 새 스냅샷으로 처음부터 재실행 (PayoutJobRunner 주석 참고)
    @Bean
    public Job payoutJob(JobRepository jobRepository,
                         Step payoutSnapshotStep, Step payoutValidateStep, Step payoutDraftStep,
                         Step payoutVerifyStep, Step payoutCommitStep, Step payoutPublishStep) {
        return new JobBuilder("payoutJob", jobRepository)
                .start(payoutSnapshotStep)
                .next(payoutValidateStep)
                .next(payoutDraftStep)
                .next(payoutVerifyStep)
                .next(payoutCommitStep)
                .next(payoutPublishStep)
                .build();
    }

    @Bean
    public Step payoutSnapshotStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                   Tasklet payoutSnapshotTasklet) {
        return new StepBuilder("payoutSnapshotStep", jobRepository)
                .tasklet(payoutSnapshotTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet payoutSnapshotTasklet(@Value("#{jobParameters['requestedAt']}") LocalDateTime requestedAt) {
        // requestedAt 없이 실행됨 = 스케줄러/API가 아닌 경로(앱 시작 시 자동 실행 등) → 의도치 않은 실행이므로 실패시킴
        if (requestedAt == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_PAYOUT_REQUESTED_AT);
        }
        return (contribution, chunkContext) -> {
            JobExecution jobExecution = contribution.getStepExecution().getJobExecution();
            LocalDateTime from = payoutService.settlementFrom();
            LocalDateTime to = payoutService.settlementTo(requestedAt.toLocalDate());

            Long snapshotId = payoutService.createSnapshot(jobExecution.getId(), from, to);
            // Step 사이에 값을 넘기는 통로: 다음 Step은 #{jobExecutionContext['snapshotId']}로 꺼냄
            jobExecution.getExecutionContext().put(SNAPSHOT_ID_KEY, snapshotId);
            return RepeatStatus.FINISHED;
        };
    }

    // 입력 검증: 틀린 후보는 실패로 기록만 하고 예외를 던지지 않음 → 나머지 후보는 draftStep으로 넘어감
    // 결과 검증(verifyStep)과 다름: 그쪽은 우리가 만든 결과를 의심함 (틀리면 코드 버그라 주문을 잠그거나, 코드 고장이면 Job 전체를 멈춤)
    @Bean
    public Step payoutValidateStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                   Tasklet payoutValidateTasklet) {
        return new StepBuilder("payoutValidateStep", jobRepository)
                .tasklet(payoutValidateTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet payoutValidateTasklet(@Value("#{jobExecutionContext['" + SNAPSHOT_ID_KEY + "']}") Long snapshotId) {
        return (contribution, chunkContext) -> {
            int failed = payoutService.validate(snapshotId);
            contribution.incrementWriteCount(failed); // write count = 실패로 기록한 후보 수
            return RepeatStatus.FINISHED;
        };
    }

    // tasklet 1회 호출 = 판매자 N명(PAYOUT_BATCH_PAYEE_SIZE) 1회차. 부하를 고려해 판매자 단위로 끊어 반복 처리
    // 트랜잭션: tasklet 트랜잭션 1개는 조회만 하고, 저장은 판매자마다 별도 트랜잭션에서 커밋 (PayoutService.draft 참고, #90)
    //   한 판매자 예외는 그 판매자만 롤백하고 UNEXPECTED_ERROR로 기록, Job은 계속 진행
    //   단 전원 실패(성공 0명)면 마지막 회차에서 Step을 실패시켜 Job FAILED (failIfEverySellerFailed)
    //   실패 기록 저장마저 실패하는 등 그 밖의 예외는 그 회차가 실패하고 Job은 FAILED로 멈춤
    //   (이미 커밋된 판매자 draft는 같은 회차·이전 회차 모두 남지만 payout은 만들어지지 않음. 재실행은 새 스냅샷으로 처음부터)
    @Bean
    public Step payoutDraftStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                Tasklet payoutDraftTasklet) {
        return new StepBuilder("payoutDraftStep", jobRepository)
                .tasklet(payoutDraftTasklet, transactionManager)
                .build();
    }

    // 조회 기간은 snapshotStep이 스냅샷으로 고정했으므로, 여기서는 스냅샷 ID만 받아 그 목록 안에서 처리
    @Bean
    @StepScope
    public Tasklet payoutDraftTasklet(@Value("#{jobExecutionContext['" + SNAPSHOT_ID_KEY + "']}") Long snapshotId) {
        // 1회 호출에 판매자 N명 draft, 남으면 CONTINUABLE로 다시 호출, 없으면 FINISHED
        return (contribution, chunkContext) -> {
            PayoutDraftResult result = payoutService.draft(snapshotId, PayoutPolicy.PAYOUT_BATCH_PAYEE_SIZE);
            if (result.sellerCount() == 0) {
                failIfEverySellerFailed(contribution.getStepExecution(), snapshotId);
                return RepeatStatus.FINISHED;
            }
            // 배치 실행 기록(BATCH_STEP_EXECUTION)에 판매자 수를 남김
            //   WRITE_COUNT      = draft에 성공한 판매자 수
            //   WRITE_SKIP_COUNT = 저장 중 예외로 건너뛰고 UNEXPECTED_ERROR로 기록한 판매자 수 (#90, Job은 COMPLETED여도 여기서 실패를 확인)
            //   filter count가 아니라 write skip count에 두는 이유: filter는 "처리기가 일부러 걸러낸 것", skip은 "예외로 쓰지 못하고 건너뛴 것"이라 뒤쪽이 맞음
            contribution.incrementWriteCount(result.draftedSellerCount());
            contribution.incrementWriteSkipCount(result.failedSellerCount());
            return RepeatStatus.CONTINUABLE;
        };
    }

    // draft를 시도한 판매자가 있는데 전원 실패했으면(성공 0명) Step을 실패시켜 Job을 FAILED로 끝냄 (#90)
    // 판매자 단위 격리는 "실패한 판매자를 빼고 나머지를 정산"하려는 것인데, 전원 실패면 지켜 낼 정산이 없어 격리의 이점이 없음
    // 전원 실패는 대부분 공통 원인(코드 버그, DB 장애)이라 COMPLETED로 두면 정산 0건인데 정상 종료로 보여 놓치기 쉬움
    //   일부만 실패하면 정상 판매자 정산을 위해 COMPLETED 유지 (실패 수는 WRITE_SKIP_COUNT, 반복 실패 알림은 #52)
    //   셈에는 draft 예외(UNEXPECTED_ERROR)만 들어감: validate 실패(차감 기록 없음, 금액 불일치)만 있는 날은 draft 대상이 없어 해당 없음
    //   대상 판매자가 1명뿐인 날 그 1명이 실패해도 FAILED (#90 이전과 같은 결과, "예상 못 한 오류로 정산 0건"이라는 사실은 같음)
    // 마지막 회차(남은 판매자 없음)에서 확인: 앞 회차들의 수가 Step 실행 기록에 누적돼 있음 (tasklet 1회 = contribution 1개, 회차마다 Step에 합산)
    // 실패 기록(UNEXPECTED_ERROR)은 잠그지 않으므로, 원인을 고친 뒤 재실행하면 새 스냅샷에서 다시 처리됨
    private void failIfEverySellerFailed(StepExecution stepExecution, Long snapshotId) {
        if (stepExecution.getWriteCount() == 0 && stepExecution.getWriteSkipCount() > 0) {
            // 사용자 입력이 아닌 배치 내부 상태라 표준 예외 (exception.md: 값의 출처 기준). 메시지는 Step 실행 기록(EXIT_MESSAGE)에 남음
            throw new IllegalStateException("draft 대상 판매자가 모두 실패해 정산할 판매자가 없습니다. snapshotId="
                    + snapshotId + ", 실패 판매자 수=" + stepExecution.getWriteSkipCount());
        }
    }

    // 어긋난 후보의 주문은 실패로 기록하고(별도 트랜잭션) 나머지는 commit으로 넘김 (#290, PayoutService.verify 참고)
    // 실패하면(코드 고장, 정산 0건) Job이 FAILED로 멈춰 commit·publish가 실행되지 않음
    @Bean
    public Step payoutVerifyStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                 Tasklet payoutVerifyTasklet) {
        return new StepBuilder("payoutVerifyStep", jobRepository)
                .tasklet(payoutVerifyTasklet, transactionManager)
                .build();
    }

    // 배치 실행 기록(BATCH_STEP_EXECUTION)에 남기는 수
    //   WRITE_COUNT      = 결과 검증을 통과해 commitStep이 정산할 draft 항목 수
    //   WRITE_SKIP_COUNT = 결과 검증 실패로 새로 기록한 후보 수 (함께 보류된 후보 포함). 일부만 걸리면 Job은 COMPLETED라 여기서 확인
    @Bean
    @StepScope
    public Tasklet payoutVerifyTasklet(@Value("#{jobExecutionContext['" + SNAPSHOT_ID_KEY + "']}") Long snapshotId) {
        return (contribution, chunkContext) -> {
            PayoutVerifyResult result = payoutService.verify(snapshotId);
            // 수를 먼저 남긴 뒤 실패 판정: 예외로 끝나도 이번 호출의 수는 Step 실행 기록에 반영됨 (아래 메서드 주석)
            contribution.incrementWriteCount(result.remainingItemCount());
            contribution.incrementWriteSkipCount(result.recordedFailureCount());
            failIfEveryOrderExcluded(result, snapshotId);
            return RepeatStatus.FINISHED;
        };
    }

    // 결과 검증에 걸린 주문이 있는데 정산할 것이 0건 남으면 Step을 실패시켜 Job을 FAILED로 끝냄 (#290)
    // 기준은 draft 전원 실패(failIfEverySellerFailed)와 같음: 지켜 낼 정산이 없는 전부 실패는 대부분 공통 원인(코드 버그)이라
    //   COMPLETED로 두면 정산 0건인데 정상 종료로 보여 놓치기 쉬움. 일부만 걸리면 정상 주문 정산을 위해 COMPLETED 유지
    // 실패 기록·잠금은 verify가 별도 트랜잭션으로 이미 저장해 이 예외로 롤백되지 않음
    // 수(WRITE_SKIP_COUNT = 걸린 후보 수)는 FAILED여도 Step 실행 기록에 남음
    //   Spring Batch(6.0.5 TaskletStep)는 tasklet이 예외로 끝나도 finally에서 이번 호출의 수를 Step 실행 기록에 더함 → 그래서 판정 전에 수를 올림
    //   예외 메시지에도 수를 담아 EXIT_MESSAGE만 봐도 알 수 있게 함
    private void failIfEveryOrderExcluded(PayoutVerifyResult result, Long snapshotId) {
        if (result.everyOrderExcluded()) {
            // 사용자 입력이 아닌 배치 내부 상태라 표준 예외 (exception.md: 값의 출처 기준). 메시지는 Step 실행 기록(EXIT_MESSAGE)에 남음
            // TODO(#52): 결과 검증 실패는 코드 버그 신호라 즉시 알림이 필요. 지금은 Job만 FAILED로 끝남
            throw new IllegalStateException("결과 검증에 걸린 주문을 빼고 나니 정산할 항목이 없습니다. snapshotId="
                    + snapshotId + ", 뺀 주문 수=" + result.excludedOrderCount()
                    + ", 새로 기록한 실패 수=" + result.recordedFailureCount());
        }
    }

    // tasklet 1회 = 트랜잭션 1개. commitStep은 반복(CONTINUABLE) 없이 한 번에 끝내야 함
    // (회차로 나누면 트랜잭션이 나뉘어 시스템 몫 중복 방지가 깨짐, PayoutService.commit 주석 참고)
    @Bean
    public Step payoutCommitStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                 Tasklet payoutCommitTasklet) {
        return new StepBuilder("payoutCommitStep", jobRepository)
                .tasklet(payoutCommitTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet payoutCommitTasklet(@Value("#{jobExecutionContext['" + SNAPSHOT_ID_KEY + "']}") Long snapshotId) {
        return (contribution, chunkContext) -> {
            payoutService.commit(snapshotId);
            return RepeatStatus.FINISHED;
        };
    }

    // 확정(commitStep)과 발행을 Step으로 분리: 검증·확정이 끝난 정산만 지갑으로 전달
    // 이 Step만 실패하면 확정된 정산의 이벤트가 누락될 수 있음 → TODO(#52), PayoutService.publish 주석 참고
    @Bean
    public Step payoutPublishStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                  Tasklet payoutPublishTasklet) {
        return new StepBuilder("payoutPublishStep", jobRepository)
                .tasklet(payoutPublishTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet payoutPublishTasklet(@Value("#{jobExecutionContext['" + SNAPSHOT_ID_KEY + "']}") Long snapshotId) {
        return (contribution, chunkContext) -> {
            int published = payoutService.publish(snapshotId);
            contribution.incrementWriteCount(published); // write count = 발행한 이벤트 수
            return RepeatStatus.FINISHED;
        };
    }

}
