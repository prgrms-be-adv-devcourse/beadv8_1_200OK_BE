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


    @Bean
    //실패시 멈춤. 지금 상황에서는 성공 가정으로처리.
    //만일 실패할 경우 시나리오 적용 차후 적용 예정.
    //부하문제 생각해서 일단 정산사람 100명(n)로 제한.
    public Step payoutStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                           Tasklet payoutTasklet) {
        return new StepBuilder("payoutStep", jobRepository)
                .tasklet(payoutTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet payoutTasklet(@Value("#{jobParameters['requestedAt']}") LocalDateTime requestedAt) {
        if (requestedAt == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_PAYOUT_REQUESTED_AT);
        }
        LocalDateTime from = payoutService.settlementFrom();
        LocalDateTime to = payoutService.settlementTo(requestedAt.toLocalDate());

        return (contribution, chunkContext) -> { // tasklet 방식: 1회 호출에 판매자 N명 정산, 남으면 반복
            int settled = payoutService.settle(from, to, PayoutPolicy.PAYOUT_BATCH_PAYEE_SIZE);
            if (settled == 0) {
                return RepeatStatus.FINISHED;//종료
            }
            contribution.incrementWriteCount(settled); // 실행 기록에 처리 수 누적. 지금 메모리? 이거 테이블변화? 의논 필요?
            return RepeatStatus.CONTINUABLE;//아직 정산 대상 남은
        };
    }

}
