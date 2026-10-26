package com.ok.payout.service;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.payout.service.dto.PayoutDraftResult;
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
    //   snapshot : 이번 정산 대상을 스냅샷 목록으로 고정 (단일 트랜잭션, 잠긴 주문의 후보 제외)
    //   validate : 지갑 차감 기록이 없거나 금액이 맞지 않는 주문의 후보를 실패로 기록 (이번 정산에서 빠짐, Job은 계속 진행)
    //              금액이 맞지 않는 주문은 잠겨 다음 배치부터 snapshot에서 빠짐
    //   draft    : 판매자 N명 단위 회차 반복으로 임시 정산 생성 (실패로 기록된 후보 제외)
    //              판매자마다 트랜잭션을 따로 둬서, 한 판매자에서 예외가 나면 그 판매자만 실패로 기록하고 계속 진행 (#90)
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

    // tasklet 1회 호출 = 판매자 N명(PAYOUT_BATCH_PAYEE_SIZE) 1회차. 부하를 고려해 판매자 단위로 끊어 반복 처리
    // 트랜잭션: tasklet 트랜잭션 1개는 조회만 하고, 저장은 판매자마다 별도 트랜잭션에서 커밋 (PayoutService.draft 참고, #90)
    //   한 판매자 예외는 그 판매자만 롤백하고 UNEXPECTED_ERROR로 기록, Job은 계속 진행
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
