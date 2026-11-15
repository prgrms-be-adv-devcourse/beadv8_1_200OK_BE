package com.ok.payout.application.service;

import com.ok.payout.application.port.in.PayoutDepositCheckAnswerCommand;
import com.ok.payout.application.port.in.PayoutDepositCheckRunResult;
import com.ok.payout.application.port.out.PayoutDepositCheckRequested;
import com.ok.payout.application.port.out.PayoutEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

// 대사 순서 정하기 (#619): 묶음을 이어서 묻기, 보내기 실패 때 멈추고 되돌리기, 답 처리는 DB 반영만(#620)
@ExtendWith(MockitoExtension.class)
class PayoutDepositCheckServiceTest {

    @Mock
    private PayoutDepositCheckRecordService payoutDepositCheckRecordService;

    @Mock
    private PayoutEventPublisher payoutEventPublisher;

    @InjectMocks
    private PayoutDepositCheckService sut;

    @Test
    void 대기_줄을_묶음마다_앞_묶음의_마지막_id_뒤부터_이어서_묻는다() {
        // given: 첫 묶음 2건(줄 1·2), 둘째 묶음 1건(줄 3), 그 뒤는 없음
        PayoutDepositCheckPlan first = plan(10L, List.of(1L, 2L));
        PayoutDepositCheckPlan second = plan(11L, List.of(3L));
        given(payoutDepositCheckRecordService.closeExhausted()).willReturn(1);
        given(payoutDepositCheckRecordService.prepareNextBatch(0L)).willReturn(Optional.of(first));
        given(payoutDepositCheckRecordService.prepareNextBatch(2L)).willReturn(Optional.of(second));
        given(payoutDepositCheckRecordService.prepareNextBatch(3L)).willReturn(Optional.empty());

        // when
        PayoutDepositCheckRunResult result = sut.runCheck();

        // then
        assertThat(result).isEqualTo(new PayoutDepositCheckRunResult(1, 3, 2, false));
        then(payoutEventPublisher).should().publishDepositCheckRequested(first.requested());
        then(payoutEventPublisher).should().publishDepositCheckRequested(second.requested());
        then(payoutDepositCheckRecordService).should(never()).undoAsked(any());
    }

    @Test
    void 질문을_보내지_못하면_그_묶음의_물음을_되돌리고_남은_줄은_묻지_않고_멈춘다() {
        // given
        PayoutDepositCheckPlan first = plan(10L, List.of(1L, 2L));
        given(payoutDepositCheckRecordService.prepareNextBatch(0L)).willReturn(Optional.of(first));
        willThrow(new IllegalStateException("브로커 응답 없음"))
                .given(payoutEventPublisher).publishDepositCheckRequested(first.requested());

        // when
        PayoutDepositCheckRunResult result = sut.runCheck();

        // then: 물은 정산 0건, 멈춤, 되돌림. 다음 묶음은 만들지 않음
        assertThat(result).isEqualTo(new PayoutDepositCheckRunResult(0, 0, 0, true));
        then(payoutDepositCheckRecordService).should().undoAsked(first);
        then(payoutDepositCheckRecordService).should(never()).prepareNextBatch(2L);
    }

    @Test
    void 되돌리기마저_실패해도_예외를_내지_않고_멈춘다() {
        // given
        PayoutDepositCheckPlan first = plan(10L, List.of(1L));
        given(payoutDepositCheckRecordService.prepareNextBatch(0L)).willReturn(Optional.of(first));
        willThrow(new IllegalStateException("브로커 응답 없음"))
                .given(payoutEventPublisher).publishDepositCheckRequested(first.requested());
        willThrow(new IllegalStateException("DB 끊김")).given(payoutDepositCheckRecordService).undoAsked(first);

        // when
        PayoutDepositCheckRunResult result = sut.runCheck();

        // then
        assertThat(result.stoppedBySendFailure()).isTrue();
    }

    @Test
    void 지갑_답이_같은_줄을_먼저_고쳐_저장이_거절되면_그_단계를_한_번_더_한다() {
        // given: 닫기 첫 시도는 낙관적 잠금으로 거절, 두 번째는 1줄 닫음
        given(payoutDepositCheckRecordService.closeExhausted())
                .willThrow(new OptimisticLockingFailureException("다른 트랜잭션이 먼저 고침"))
                .willReturn(1);
        given(payoutDepositCheckRecordService.prepareNextBatch(0L)).willReturn(Optional.empty());

        // when
        PayoutDepositCheckRunResult result = sut.runCheck();

        // then
        assertThat(result.closedCount()).isEqualTo(1);
    }

    @Test
    void 닫기가_두_번_다_거절돼도_묻기는_그대로_한다() {
        // given: 닫기는 두 번 다 낙관적 잠금으로 거절, 묻기 묶음은 1건
        PayoutDepositCheckPlan first = plan(10L, List.of(1L));
        given(payoutDepositCheckRecordService.closeExhausted())
                .willThrow(new OptimisticLockingFailureException("다른 트랜잭션이 먼저 고침"));
        given(payoutDepositCheckRecordService.prepareNextBatch(0L)).willReturn(Optional.of(first));
        given(payoutDepositCheckRecordService.prepareNextBatch(1L)).willReturn(Optional.empty());

        // when
        PayoutDepositCheckRunResult result = sut.runCheck();

        // then: 닫은 줄 0, 묻기는 함
        assertThat(result).isEqualTo(new PayoutDepositCheckRunResult(0, 1, 1, false));
        then(payoutEventPublisher).should().publishDepositCheckRequested(first.requested());
    }

    @Test
    void 답_처리는_입금_메시지를_직접_보내지_않고_DB_반영만_한다() {
        // given: "없음" 정산의 다시 보내기는 답 반영 트랜잭션이 outbox 줄을 되돌려 짧은 간격 재발송에 맡김 (#620)
        PayoutDepositCheckAnswerCommand command = new PayoutDepositCheckAnswerCommand(10L, List.of());

        // when
        sut.handleAnswer(command);

        // then: 답 처리 중 Kafka를 기다리지 않음 (max.poll.interval을 넘지 않게)
        then(payoutDepositCheckRecordService).should().applyAnswer(command);
        then(payoutEventPublisher).should(never()).publishSellerPayoutCreated(any());
    }

    // 줄 ID 목록으로 묶음을 만듦. 마지막 줄 ID가 다음 묶음의 시작점
    private static PayoutDepositCheckPlan plan(Long checkId, List<Long> rowIds) {
        List<PayoutDepositCheckRequested.Item> items = new ArrayList<>();
        for (Long rowId : rowIds) {
            items.add(new PayoutDepositCheckRequested.Item(rowId + 100, 5L, 9_700L));
        }
        return new PayoutDepositCheckPlan(new PayoutDepositCheckRequested(checkId, items), rowIds, rowIds.get(rowIds.size() - 1));
    }
}
