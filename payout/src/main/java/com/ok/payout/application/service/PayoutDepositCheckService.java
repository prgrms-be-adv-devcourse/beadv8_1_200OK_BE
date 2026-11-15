package com.ok.payout.application.service;

import com.ok.payout.application.port.in.PayoutDepositCheckAnswerCommand;
import com.ok.payout.application.port.in.PayoutDepositCheckRunResult;
import com.ok.payout.application.port.in.PayoutDepositCheckUseCase;
import com.ok.payout.application.port.out.PayoutEventPublisher;
import com.ok.payout.application.port.out.SellerPayoutCreated;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 정산·지갑 대사 유스케이스 구현 (#619, 상위 #308). 지갑과의 약속은 PR #548 문서 4절과 #308 11/15 댓글이다.
 * 이 클래스는 트랜잭션을 만들지 않고 순서만 정한다. DB 작업은 줄마다·묶음마다 짧은 트랜잭션({@link PayoutDepositCheckRecordService})으로 하고,
 * Kafka 보내기는 그 사이 트랜잭션 밖에서 한다 ({@link PayoutService#publishPending()}과 같은 방식).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutDepositCheckService implements PayoutDepositCheckUseCase {

    private final PayoutDepositCheckRecordService payoutDepositCheckRecordService;
    private final PayoutEventPublisher payoutEventPublisher;

    @Override
    public PayoutDepositCheckRunResult runCheck() {
        // 묻기 전에 먼저 닫음: 3번째 질문에도 하루를 기다려 준 뒤라, 그 답이 끝내 오지 않은 줄만 닫힘
        int closed = payoutDepositCheckRecordService.closeExhausted();

        int asked = 0;
        int batches = 0;
        boolean stopped = false;
        Long afterCheckRowId = 0L;
        while (true) {
            Optional<PayoutDepositCheckPlan> next = payoutDepositCheckRecordService.prepareNextBatch(afterCheckRowId);
            if (next.isEmpty()) {
                break;
            }
            PayoutDepositCheckPlan plan = next.get();
            try {
                payoutEventPublisher.publishDepositCheckRequested(plan.requested());
            } catch (RuntimeException e) {
                // 브로커 장애면 다음 묶음도 실패하므로 멈춤. 남은 줄은 다음 날 다시 물음 (outbox 발송 실패도 같은 장애로 함께 드러남)
                log.warn("대사 질문을 Kafka로 보내지 못해 이번 대사를 멈춤. checkId={}, 묶음 건수={}",
                        plan.requested().checkId(), plan.checkRowIds().size(), e);
                undoAskedQuietly(plan);
                stopped = true;
                break;
            }
            asked += plan.checkRowIds().size();
            batches++;
            afterCheckRowId = plan.lastCheckRowId();
        }
        return new PayoutDepositCheckRunResult(closed, asked, batches, stopped);
    }

    @Override
    public void handleAnswer(PayoutDepositCheckAnswerCommand command) {
        List<SellerPayoutCreated> resend = payoutDepositCheckRecordService.applyAnswer(command);
        for (SellerPayoutCreated created : resend) {
            try {
                // 같은 정산이 두 번 가도 지갑은 payoutId로 한 번만 입금함(#584). 그래서 outbox를 거치지 않고 바로 보냄
                payoutEventPublisher.publishSellerPayoutCreated(created);
                log.info("대사에서 지갑 입금이 없어 입금 메시지를 다시 보냄. payoutId={}", created.payoutId());
            } catch (RuntimeException e) {
                // 못 보내도 다음 날 대사가 다시 "없음"을 받고 또 보냄. 답 처리는 이미 커밋돼 되돌리지 않음
                log.warn("대사에서 입금 메시지를 다시 보내지 못함. 다음 대사에서 다시 보냄. payoutId={}", created.payoutId(), e);
            }
        }
    }

    // 되돌리기마저 실패하면 그날은 물은 횟수에 들어감. 대사를 멈추는 길이라 예외를 밖으로 내지 않음
    private void undoAskedQuietly(PayoutDepositCheckPlan plan) {
        try {
            payoutDepositCheckRecordService.undoAsked(plan);
        } catch (RuntimeException e) {
            log.warn("보내지 못한 대사 질문의 물은 횟수를 되돌리지 못함. checkId={}", plan.requested().checkId(), e);
        }
    }
}
