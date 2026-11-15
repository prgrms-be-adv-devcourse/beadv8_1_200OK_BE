package com.ok.payout.application.service;

import com.ok.payout.application.port.in.PayoutDepositCheckAnswerCommand;
import com.ok.payout.application.port.out.PayoutDepositCheckBatchRepository;
import com.ok.payout.application.port.out.PayoutDepositCheckRepository;
import com.ok.payout.application.port.out.PayoutDepositCheckRequested;
import com.ok.payout.application.port.out.PayoutRepository;
import com.ok.payout.application.port.out.SellerPayoutCreated;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutDepositAnswerOutcome;
import com.ok.payout.domain.PayoutDepositCheck;
import com.ok.payout.domain.PayoutDepositCheckBatch;
import com.ok.payout.domain.PayoutDepositCheckStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 정산·지갑 대사의 DB 작업을 짧은 트랜잭션 단위로 한다 (#619).
 * Kafka 보내기는 트랜잭션 밖({@link PayoutDepositCheckService})에서 한다. 보내기가 오래 걸려도(최대 120초) DB 연결을 잡고 있지 않게 하려고서다.
 * {@link PayoutDepositCheckService}와 빈을 나눈 이유는 {@link PayoutOutboxService}와 같다(같은 클래스 안 호출은 {@code @Transactional}이 무시됨).
 * <p>
 * 관리자 확인 필요가 되면 {@code ERROR}로 남긴다. 팀 로그 규칙({@code ERROR}는 처리되지 못한 예외)과 다르게 쓰는 이유:
 * 판매자에게 돈이 들어가지 않았거나 다른 금액·다른 사람에게 들어간 건이라 사람이 꼭 봐야 하고, 대사가 자동으로 고치지 않는다(#308 11/15 댓글 안건 4).
 * 지갑 실패 기록도 같은 이유로 {@code ERROR}를 쓴다. 메시지는 {@code [정산-대사불일치]}로 시작해 한 번에 찾게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayoutDepositCheckRecordService {

    private static final String MISMATCH_TAG = "[정산-대사불일치]";

    private final PayoutDepositCheckRepository payoutDepositCheckRepository;
    private final PayoutDepositCheckBatchRepository payoutDepositCheckBatchRepository;
    private final PayoutRepository payoutRepository;
    private final Clock clock;

    // 3번을 다 물었는데 마지막 질문의 답까지 오지 않은 줄을 관리자 확인 필요로 넘김
    @Transactional
    public int closeExhausted() {
        List<PayoutDepositCheck> exhausted = payoutDepositCheckRepository.findAllByStatusAndAttemptCountGreaterThanEqual(
                PayoutDepositCheckStatus.WAITING, PayoutDepositCheck.MAX_ATTEMPTS);
        int closed = 0;
        for (PayoutDepositCheck check : exhausted) {
            if (check.closeIfNoAttemptsLeft()) {
                closed++;
                logNeedsReview(check);
            }
        }
        return closed;
    }

    // 다음 질문 묶음을 만듦: 묶음 번호를 받고, 담은 줄마다 "이 번호로 물음"을 적음. 대기 줄이 더 없으면 빈 값
    // 커밋한 뒤에 보내야 지갑의 빠른 답이 이 번호를 찾을 수 있어, 보내기는 이 트랜잭션이 끝난 뒤 호출하는 쪽이 함
    @Transactional
    public Optional<PayoutDepositCheckPlan> prepareNextBatch(Long afterCheckRowId) {
        List<PayoutDepositCheck> checks = payoutDepositCheckRepository.findTop500ByStatusAndIdGreaterThanOrderById(
                PayoutDepositCheckStatus.WAITING, afterCheckRowId);
        if (checks.isEmpty()) {
            return Optional.empty();
        }
        PayoutDepositCheckBatch batch = payoutDepositCheckBatchRepository.save(PayoutDepositCheckBatch.of(checks.size()));
        LocalDateTime now = LocalDateTime.now(clock);
        List<PayoutDepositCheckRequested.Item> items = new ArrayList<>();
        List<Long> checkRowIds = new ArrayList<>();
        Long lastCheckRowId = afterCheckRowId;
        for (PayoutDepositCheck check : checks) {
            check.recordAsked(batch.getId(), now);
            items.add(new PayoutDepositCheckRequested.Item(check.getPayoutId(), check.getPayeeId(), check.getAmount()));
            checkRowIds.add(check.getId());
            lastCheckRowId = check.getId();
        }
        return Optional.of(new PayoutDepositCheckPlan(
                new PayoutDepositCheckRequested(batch.getId(), items), checkRowIds, lastCheckRowId));
    }

    // Kafka로 보내지 못한 묶음의 "물음"을 되돌림 (보내지 못한 날은 물은 횟수에 넣지 않음)
    @Transactional
    public void undoAsked(PayoutDepositCheckPlan plan) {
        Long checkId = plan.requested().checkId();
        for (PayoutDepositCheck check : payoutDepositCheckRepository.findAllByIdIn(plan.checkRowIds())) {
            check.undoAsked(checkId);
        }
    }

    // 답 묶음 하나를 반영하고, 입금 메시지를 다시 보낼 정산을 돌려줌 (보내기는 트랜잭션 밖에서 호출하는 쪽이 함)
    @Transactional
    public List<SellerPayoutCreated> applyAnswer(PayoutDepositCheckAnswerCommand command) {
        List<Long> payoutIds = new ArrayList<>();
        for (PayoutDepositCheckAnswerCommand.Item item : command.items()) {
            payoutIds.add(item.payoutId());
        }
        Map<Long, PayoutDepositCheck> checkByPayoutId = new HashMap<>();
        for (PayoutDepositCheck check : payoutDepositCheckRepository.findAllByPayoutIdIn(payoutIds)) {
            checkByPayoutId.put(check.getPayoutId(), check);
        }

        List<Long> resendPayoutIds = new ArrayList<>();
        for (PayoutDepositCheckAnswerCommand.Item item : command.items()) {
            PayoutDepositCheck check = checkByPayoutId.get(item.payoutId());
            if (check == null) {
                // 정산이 묻지 않은 정산의 답. 지갑이 잘못 답했거나 정산 쪽 줄이 없어진 것이라 건너뛰되 남김
                log.warn("대사 줄이 없는 정산의 지갑 답이라 건너뜀. checkId={}, payoutId={}", command.checkId(), item.payoutId());
                continue;
            }
            PayoutDepositAnswerOutcome outcome = check.applyAnswer(
                    command.checkId(), item.found(), item.payeeId(), item.amount(), item.depositedAt());
            switch (outcome) {
                case IGNORED -> log.info("최근 질문의 답이 아니거나 이미 처리한 답이라 버림. checkId={}, payoutId={}, lastCheckId={}, status={}",
                        command.checkId(), item.payoutId(), check.getLastCheckId(), check.getStatus());
                case MATCHED -> log.info("대사 일치. payoutId={}, attemptCount={}", item.payoutId(), check.getAttemptCount());
                case NOT_FOUND_RETRY -> resendPayoutIds.add(item.payoutId());
                case NEEDS_REVIEW -> logNeedsReview(check);
            }
        }
        return toSellerPayoutCreated(resendPayoutIds);
    }

    private List<SellerPayoutCreated> toSellerPayoutCreated(List<Long> payoutIds) {
        if (payoutIds.isEmpty()) {
            return List.of();
        }
        List<SellerPayoutCreated> created = new ArrayList<>();
        for (Payout payout : payoutRepository.findAllByIdIn(payoutIds)) {
            created.add(new SellerPayoutCreated(payout.getId(), payout.getPayeeId(), payout.getAmount(), payout.getCreatedAt()));
        }
        return created;
    }

    private void logNeedsReview(PayoutDepositCheck check) {
        log.error("{} 관리자 확인 필요. payoutId={}, reason={}, payeeId={}, amount={}, walletPayeeId={}, walletAmount={}, attemptCount={}, notFoundCount={}",
                MISMATCH_TAG, check.getPayoutId(), check.getMismatchReason(), check.getPayeeId(), check.getAmount(),
                check.getWalletPayeeId(), check.getWalletAmount(), check.getAttemptCount(), check.getNotFoundCount());
    }
}
