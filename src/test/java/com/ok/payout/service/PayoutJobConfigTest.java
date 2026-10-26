package com.ok.payout.service;

import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.service.dto.PayoutDraftResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.given;

// 단위 테스트: draftStep tasklet이 회차 결과를 배치 실행 기록(StepContribution)에 어떻게 남기는지만 확인 (#90)
// 판매자 실패를 실제 Job 실행 중에 만들 방법이 없어(스냅샷 ID를 미리 알 수 없고, 테스트 DB 계정은 트리거를 만들 수 없음) 이 연결부를 따로 확인함
// 판매자 단위 격리 자체(트랜잭션 분리, 실패 기록)는 PayoutSettleIntegrationTest에서 실제 DB로 확인
@ExtendWith(MockitoExtension.class)
class PayoutJobConfigTest {

    private static final Long SNAPSHOT_ID = 12L;

    @Mock
    private PayoutService payoutService;

    @Test
    void draft_회차에서_성공한_판매자는_write_count_실패한_판매자는_filter_count로_남기고_다음_회차를_부른다() throws Exception {
        // given: 이번 회차 판매자 3명 중 1명 실패
        given(payoutService.draft(SNAPSHOT_ID, PayoutPolicy.PAYOUT_BATCH_PAYEE_SIZE)).willReturn(new PayoutDraftResult(3, 1));
        StepContribution contribution = contribution();

        // when
        RepeatStatus sut = new PayoutJobConfig(payoutService).payoutDraftTasklet(SNAPSHOT_ID).execute(contribution, null);

        // then
        assertThat(sut).isEqualTo(RepeatStatus.CONTINUABLE);
        assertSoftly(softly -> {
            softly.assertThat(contribution.getWriteCount()).isEqualTo(2L);
            softly.assertThat(contribution.getFilterCount()).isEqualTo(1L);
        });
    }

    @Test
    void 이번_회차의_판매자가_모두_실패해도_다음_회차를_부른다() throws Exception {
        // given: 판매자 2명 모두 실패 (실패한 판매자는 실패 기록 때문에 다음 회차에서 다시 뽑히지 않음)
        given(payoutService.draft(SNAPSHOT_ID, PayoutPolicy.PAYOUT_BATCH_PAYEE_SIZE)).willReturn(new PayoutDraftResult(2, 2));
        StepContribution contribution = contribution();

        // when
        RepeatStatus sut = new PayoutJobConfig(payoutService).payoutDraftTasklet(SNAPSHOT_ID).execute(contribution, null);

        // then: 판매자 수가 0이 아니면 계속. 끝내는 기준은 "남은 판매자 없음"이지 "성공한 판매자 없음"이 아님
        assertThat(sut).isEqualTo(RepeatStatus.CONTINUABLE);
    }

    @Test
    void 남은_판매자가_없으면_draft_Step을_끝낸다() throws Exception {
        // given
        given(payoutService.draft(SNAPSHOT_ID, PayoutPolicy.PAYOUT_BATCH_PAYEE_SIZE)).willReturn(new PayoutDraftResult(0, 0));
        StepContribution contribution = contribution();

        // when
        RepeatStatus sut = new PayoutJobConfig(payoutService).payoutDraftTasklet(SNAPSHOT_ID).execute(contribution, null);

        // then
        assertThat(sut).isEqualTo(RepeatStatus.FINISHED);
    }

    // tasklet에 넘길 실행 기록 (Spring 없이 직접 만듦)
    private StepContribution contribution() {
        JobExecution jobExecution = new JobExecution(1L, new JobInstance(1L, "payoutJob"), new JobParameters());
        return new StepContribution(new StepExecution("payoutDraftStep", jobExecution));
    }
}
