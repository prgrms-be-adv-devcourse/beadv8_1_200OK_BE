package com.ok.payout.service;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.PayoutErrorCode;
import com.ok.payout.domain.PayoutPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
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

    private final PayoutService payoutService;



    @Bean
    public Job payoutJob(JobRepository jobRepository, Step payoutStep) {
        return new JobBuilder("payoutJob", jobRepository)
                .start(payoutStep)
                .build();
    }


    // tasklet 1회 호출 = 1 트랜잭션. 부하를 고려해 판매자 N명(PAYOUT_BATCH_PAYEE_SIZE) 단위로 끊어 반복 처리
    // 예외 시 해당 회차만 롤백되고 Job은 FAILED로 멈춤 (이전 회차는 커밋 유지, 실패 처리는 #52)
    @Bean
    public Step payoutStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                           Tasklet payoutTasklet) {
        return new StepBuilder("payoutStep", jobRepository)
                .tasklet(payoutTasklet, transactionManager)
                .build();
    }

    // @StepScope: Step 시작 시 1번 생성되므로 조회 기간(from, to)을 여기서 1번만 계산해 고정
    // (회차마다 계산하면 앞 회차가 만든 Payout 때문에 from이 바뀜)
    @Bean
    @StepScope
    public Tasklet payoutTasklet(@Value("#{jobParameters['requestedAt']}") LocalDateTime requestedAt) {
        // requestedAt 없이 실행됨 = 스케줄러/API가 아닌 경로(앱 시작 시 자동 실행 등) → 의도치 않은 실행이므로 실패시킴
        if (requestedAt == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_PAYOUT_REQUESTED_AT);
        }
        LocalDateTime from = payoutService.settlementFrom();
        LocalDateTime to = payoutService.settlementTo(requestedAt.toLocalDate());

        // 1회 호출에 판매자 N명 정산, 남으면 CONTINUABLE로 다시 호출, 없으면 FINISHED
        return (contribution, chunkContext) -> {
            int settled = payoutService.settle(from, to, PayoutPolicy.PAYOUT_BATCH_PAYEE_SIZE);
            if (settled == 0) {
                return RepeatStatus.FINISHED;
            }
            contribution.incrementWriteCount(settled); // write count = 정산한 판매자 수
            return RepeatStatus.CONTINUABLE;
        };
    }

}
