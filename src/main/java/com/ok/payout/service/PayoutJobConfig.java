package com.ok.payout.service;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.exception.PayoutErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
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
    //   snapshot : 이번 정산 대상을 스냅샷 목록으로 고정 (단일 트랜잭션)
    //   validate : 지갑 차감 기록이 없는 주문의 후보를 실패로 기록 (이번 정산에서 빠짐, Job은 계속 진행)
    //   draft    : 판매자 N명 단위 회차 반복으로 임시 정산 생성 (실패로 기록된 후보 제외)
    //   verify   : 스냅샷 목록과 임시 정산 비교 (불일치면 여기서 멈춤 → payout 미생성, 이벤트 미발행)
    //   commit   : 임시 정산을 payout·payout_item으로 확정 (단일 트랜잭션, 분리 금지)
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
    // 결과 검증(verifyStep)과 다름: 그쪽은 우리가 만든 결과를 의심하고, 틀리면 Job 전체를 멈춤
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

    // tasklet 1회 호출 = 1 트랜잭션. 부하를 고려해 판매자 N명(PAYOUT_BATCH_PAYEE_SIZE) 단위로 끊어 반복 처리
    // 예외 시 해당 회차만 롤백되고 Job은 FAILED로 멈춤 (이전 회차 draft는 남지만 payout은 만들어지지 않음)
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
            int drafted = payoutService.draft(snapshotId, PayoutPolicy.PAYOUT_BATCH_PAYEE_SIZE);
            if (drafted == 0) {
                return RepeatStatus.FINISHED;
            }
            contribution.incrementWriteCount(drafted); // write count = draft한 판매자 수
            return RepeatStatus.CONTINUABLE;
        };
    }

    // 검증만 하고 아무것도 저장하지 않음. 실패하면 Job이 FAILED로 멈춰 commit·publish가 실행되지 않음
    @Bean
    public Step payoutVerifyStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                 Tasklet payoutVerifyTasklet) {
        return new StepBuilder("payoutVerifyStep", jobRepository)
                .tasklet(payoutVerifyTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet payoutVerifyTasklet(@Value("#{jobExecutionContext['" + SNAPSHOT_ID_KEY + "']}") Long snapshotId) {
        return (contribution, chunkContext) -> {
            payoutService.verify(snapshotId);
            return RepeatStatus.FINISHED;
        };
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
    // 이 Step만 실패하면 확정된 정산의 이벤트가 누락될 수 있음 → TODO(#67), PayoutService.publish 주석 참고
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
