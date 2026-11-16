package com.ok.payout.application.service;

import com.ok.payout.application.port.in.PayoutDepositCheckAnswerCommand;
import com.ok.payout.application.port.in.PayoutDepositCheckReviewResult;
import com.ok.payout.application.port.in.PayoutDepositCheckReviewSearchQuery;
import com.ok.payout.application.port.in.PayoutDepositCheckRunResult;
import com.ok.payout.application.port.in.PayoutDepositCheckUseCase;
import com.ok.payout.application.port.out.DepositCheckReviewRow;
import com.ok.payout.application.port.out.PayoutEventPublisher;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 정산·지갑 대사 유스케이스 구현 (#619, 상위 #308). 지갑과의 약속은 PR #548 문서 4절과 #308 11/15 댓글이다.
 * 이 클래스는 트랜잭션을 만들지 않고 순서만 정한다. DB 작업은 줄마다·묶음마다 짧은 트랜잭션({@link PayoutDepositCheckRecordService})으로 하고,
 * Kafka 보내기는 그 사이 트랜잭션 밖에서 한다 ({@link PayoutService#publishPending()}과 같은 방식).
 * 예외로 관리자 목록 조회(#621)는 읽기만 하는 한 메서드라 그 메서드에만 읽기 전용 트랜잭션을 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutDepositCheckService implements PayoutDepositCheckUseCase {

    private final PayoutDepositCheckRecordService payoutDepositCheckRecordService;
    private final PayoutEventPublisher payoutEventPublisher;
    private final PayoutQueryRepository payoutQueryRepository;

    @Override
    public PayoutDepositCheckRunResult runCheck() {
        // 묻기 전에 먼저 닫음: 3번째 질문에도 하루를 기다려 준 뒤라, 그 답이 끝내 오지 않은 줄만 닫힘
        int closed = closeExhaustedOrSkip();

        int asked = 0;
        int batches = 0;
        boolean stopped = false;
        Long afterCheckRowId = 0L;
        while (true) {
            Long after = afterCheckRowId;
            Optional<PayoutDepositCheckPlan> next = retryOnceOnConflict(() -> payoutDepositCheckRecordService.prepareNextBatch(after));
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

    // 답 처리는 DB만 고치고 끝남. "입금 없음" 정산의 입금 메시지는 outbox 줄을 되돌려 짧은 간격 재발송이 보냄 (#620, applyAnswer 주석)
    @Override
    public void handleAnswer(PayoutDepositCheckAnswerCommand command) {
        payoutDepositCheckRecordService.applyAnswer(command);
    }

    // 관리자 대사 확인 목록 (#621). 목록과 개수 두 질의를 같은 읽기 전용 트랜잭션에서 읽음 (막힌 주문 목록 #569와 같은 방식)
    // 클래스에 트랜잭션을 두지 않은 이유는 클래스 주석: 대사는 줄마다 짧은 트랜잭션과 트랜잭션 밖 Kafka 보내기를 섞어 씀
    @Override
    @Transactional(readOnly = true)
    public Page<PayoutDepositCheckReviewResult> getReviewItems(PayoutDepositCheckReviewSearchQuery query) {
        Page<DepositCheckReviewRow> page = payoutQueryRepository.findDepositChecksNeedingReview(
                PageRequest.of(query.page(), query.size()));
        return page.map(PayoutQueryResultMapper::toDepositCheckReviewResult);
    }

    // 닫기가 두 번 다 거절돼도 묻기는 그대로 함. 닫기 때문에 그날 다른 정산의 질문이 통째로 빠지지 않게
    // 못 닫은 줄은 확인 대기 그대로라 다음 날 대사가 다시 닫음 (3번을 다 물어 묻기 대상 조회에서는 빠짐, prepareNextBatch)
    private int closeExhaustedOrSkip() {
        try {
            return retryOnceOnConflict(payoutDepositCheckRecordService::closeExhausted);
        } catch (OptimisticLockingFailureException e) {
            log.warn("3번 물은 대사 줄을 지갑 답 처리와 겹쳐 닫지 못함. 다음 날 다시 닫고 오늘 묻기는 그대로 함", e);
            return 0;
        }
    }

    // 같은 순간 지갑 답이 같은 줄을 먼저 고쳐 저장이 거절되면(낙관적 잠금, PayoutDepositCheck.version) 그 단계를 한 번 더 함
    // 단계 전체가 한 트랜잭션이라 거절되면 묶음 번호·물음까지 모두 롤백되고, 다시 하면 지갑 답이 반영된 값을 새로 읽음
    // 두 번째도 거절되면 예외를 그대로 올려 이번 대사를 멈춤. 남은 줄은 다음 날 다시 물음
    private static <T> T retryOnceOnConflict(Supplier<T> step) {
        try {
            return step.get();
        } catch (OptimisticLockingFailureException e) {
            log.info("대사 줄을 지갑 답 처리가 먼저 고쳐 이 단계를 한 번 더 함", e);
            return step.get();
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
