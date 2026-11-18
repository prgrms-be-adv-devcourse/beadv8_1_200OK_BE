package com.ok.wallet.application.service;

import com.ok.wallet.application.dto.ChargeConfirmResult;
import com.ok.wallet.application.dto.ChargeRecoveryResult;
import com.ok.wallet.application.dto.TossPaymentLookupResult;
import com.ok.wallet.application.dto.TossPaymentStatus;
import com.ok.wallet.application.port.in.ChargeRecoveryUseCase;
import com.ok.wallet.application.port.out.TossPaymentsClient;
import com.ok.wallet.domain.Charge;
import com.ok.wallet.domain.ChargeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 처리 중으로 멈춘 충전 건을 토스에 조회해 끝낸다. 이 클래스에는 @Transactional을 두지 않는다.
 * 승인 흐름과 같은 이유로 토스 조회는 트랜잭션 밖에서 하고, DB 반영은 ChargeTransactionService가 건마다 따로 한다.
 *
 * <p>확실하지 않으면 바꾸지 않는다. 승인된 결제를 실패로 처리하면 돈은 빠졌는데 잔액이 늘지 않으므로,
 * 토스가 승인하지 않았다고 분명히 답한 경우에만 실패로 바꾼다.
 *
 * <p>아래 로그 표시는 사람이 토스 결제를 확인해야 하는 충전 건을 운영에서 찾기 위한 것이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeRecoveryService implements ChargeRecoveryUseCase {
    // 한 번에 토스에 묻는 건수. 남은 건은 다음 회차에 확인 시각 순으로 고른다
    private static final int BATCH_SIZE = 50;
    // 이만큼 물어도 끝나지 않으면 같은 원인으로 계속 막힌 것이라 더 묻지 않고 사람에게 넘긴다
    private static final int MAX_CHECK_COUNT = 10;
    // 토스 결제창은 30분 뒤 만료되고 그 뒤 승인 가능 시간도 지나므로, 충전 요청 뒤 이만큼 지나도 결제가 없으면 승인될 수 없다
    private static final Duration NOT_FOUND_FAIL_AFTER = Duration.ofHours(1);
    // 결제창(30분)과 복구 확인(처리 중이 된 뒤 5분~약 95분)을 다 지나도 남은 건이다. 이보다 짧으면 복구가 아직 처리 중인 건까지 알린다
    private static final Duration LONG_IN_PROGRESS_AFTER = Duration.ofHours(3);
    // 알림 로그가 너무 길어지지 않게 충전 번호는 이만큼만 남긴다
    private static final int LONG_IN_PROGRESS_LIMIT = 20;
    private static final String NOT_FOUND_FAILURE_CODE = "NOT_FOUND_PAYMENT";
    private static final String HOLD = "[CHARGE_RECOVERY_HOLD] 토스 답만으로 처리할 수 없어 복구를 멈춥니다. 사람이 토스 결제를 확인해야 합니다.";
    private static final String APPLY_FAILED = "[CHARGE_RECOVERY_APPLY_FAILED] 토스 조회 결과를 충전 건에 반영하지 못했습니다.";

    private final ChargeTransactionService chargeTransaction;
    private final TossPaymentsClient tossPaymentsClient;
    private final Clock clock;

    @Override
    public ChargeRecoveryResult recover() {
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        List<Charge> targets = chargeTransaction.findDueForRecovery(BATCH_SIZE);
        // 한 건에서 오류가 나도 나머지 건은 계속 처리한다
        for (Charge target : targets) {
            boolean claimed = false;
            try {
                // 다른 서버의 복구가 먼저 맡았으면 묻지 않고 넘어간다
                claimed = chargeTransaction.claimForRecovery(target.getChargeNo());
                if (claimed) {
                    counts.merge(recoverClaimed(target), 1, Integer::sum);
                }
            } catch (RuntimeException e) {
                // 맡기나 멈춤 처리의 DB 오류다. 맡았으면 확인 시각이 미뤄져 있어 다음 확인 때, 못 맡았으면 다음 회차에 다시 고른다
                log.warn("충전 건 복구 중 오류가 나 다음에 다시 확인합니다. chargeNo={}, claimed={}",
                        target.getChargeNo(), claimed, e);
                if (claimed) {
                    counts.merge(Outcome.RETRIED, 1, Integer::sum);
                }
            }
        }
        return new ChargeRecoveryResult(
                counts.getOrDefault(Outcome.COMPLETED, 0),
                counts.getOrDefault(Outcome.FAILED, 0),
                counts.getOrDefault(Outcome.HELD, 0),
                counts.getOrDefault(Outcome.RETRIED, 0),
                counts.getOrDefault(Outcome.ALREADY_HANDLED, 0));
    }

    @Override
    public List<String> findLongInProgressChargeNos() {
        LocalDateTime createdBefore = LocalDateTime.now(clock).minus(LONG_IN_PROGRESS_AFTER);
        return chargeTransaction.findInProgressChargeNosCreatedBefore(createdBefore, LONG_IN_PROGRESS_LIMIT);
    }

    // 반영 중 오류는 다시 확인하고, 확인 상한이면 멈춘다. 확인 시각은 맡을 때 이미 미뤘으므로 다음 확인 때 다시 묻는다
    private Outcome recoverClaimed(Charge target) {
        // 맡으면서 확인 횟수를 1 늘렸다
        int checkCount = target.getCheckCount() + 1;
        TossPaymentLookupResult lookup = lookup(target.getChargeNo());
        try {
            return apply(target, checkCount, lookup);
        } catch (RuntimeException e) {
            log.error("{} chargeNo={}", APPLY_FAILED, target.getChargeNo(), e);
            return retryOrHold(target, checkCount, "반영 중 오류");
        }
    }

    // 조회 포트가 HTTP 오류는 분류해 주지만, 그 밖의 예상하지 못한 예외도 결제 상태를 알 수 없는 것으로 본다
    private TossPaymentLookupResult lookup(String chargeNo) {
        try {
            return tossPaymentsClient.findPayment(chargeNo);
        } catch (RuntimeException e) {
            log.warn("토스 결제 조회 중 예상하지 못한 오류가 나 다음에 다시 확인합니다. chargeNo={}", chargeNo, e);
            return new TossPaymentLookupResult.Unknown("토스 결제 조회 중 예상하지 못한 오류");
        }
    }

    private Outcome apply(Charge target, int checkCount, TossPaymentLookupResult lookup) {
        return switch (lookup) {
            case TossPaymentLookupResult.Found found -> applyFound(target, checkCount, found);
            case TossPaymentLookupResult.NotFound notFound -> isPastApprovalWindow(target)
                    ? fail(target, NOT_FOUND_FAILURE_CODE, "토스에 결제가 없고 승인 가능 시간이 지남")
                    : retryOrHold(target, checkCount, "토스에 결제가 아직 없음");
            case TossPaymentLookupResult.Unknown unknown -> retryOrHold(target, checkCount, unknown.reason());
        };
    }

    private Outcome applyFound(Charge target, int checkCount, TossPaymentLookupResult.Found found) {
        TossPaymentStatus status = found.status();
        return switch (status) {
            // 토스가 다른 결제 키나 금액으로 승인했다면 이 충전 건의 결제라고 믿을 수 없어 잔액에 반영하지 않는다
            case DONE -> matches(target, found)
                    ? complete(target, found)
                    : hold(target, "승인됐지만 결제 키나 금액이 충전 건과 다름");
            // 승인을 요청한 결제는 저장된 결제 키 하나뿐이다. 같은 충전 번호로 결제창을 다시 열면 시도가 따로 생기고,
            // 승인된 시도가 없으면 조회가 가장 최근 시도를 돌려준다(테스트 키로 확인, 문서에는 없음).
            // 키가 다르면 다른 시도의 상태라 승인을 요청한 결제의 상태는 이 조회로 알 수 없다. 대부분은 실패가 맞지만,
            // 토스가 승인 요청을 늦게 끝내면 승인된 결제를 실패로 바꾸게 되어 실패로 바꾸지 않는다
            case ABORTED, EXPIRED -> isRequestedPayment(target, found)
                    ? fail(target, status.name(), "토스 결제 조회 결과 승인되지 않음 " + status)
                    : hold(target, "승인되지 않은 결제의 결제 키가 충전 건과 다름 " + status);
            // 승인 뒤 취소는 우리가 하지 않는 일이고, 가상계좌는 충전에 쓰지 않는다. 어느 쪽도 자동으로 정할 수 없다
            case CANCELED, PARTIAL_CANCELED, WAITING_FOR_DEPOSIT -> hold(target, "토스 결제 상태 " + status);
            case READY, IN_PROGRESS -> retryOrHold(target, checkCount, "토스 결제 상태 " + status);
        };
    }

    private static boolean matches(Charge target, TossPaymentLookupResult.Found found) {
        return isRequestedPayment(target, found) && target.hasAmount(found.totalAmount());
    }

    private static boolean isRequestedPayment(Charge target, TossPaymentLookupResult.Found found) {
        return found.paymentKey() != null && found.paymentKey().equals(target.getPaymentKey());
    }

    private boolean isPastApprovalWindow(Charge target) {
        return !LocalDateTime.now(clock).isBefore(target.getCreatedAt().plus(NOT_FOUND_FAIL_AFTER));
    }

    private Outcome complete(Charge target, TossPaymentLookupResult.Found found) {
        ChargeConfirmResult result = chargeTransaction.complete(target.getMemberId(), target.getChargeNo(), found.paymentKey());
        if (!ChargeStatus.COMPLETED.name().equals(result.status())) {
            return alreadyHandled(target, result);
        }
        log.info("복구가 토스 승인을 확인해 충전 건을 완료했습니다. chargeNo={}", target.getChargeNo());
        return Outcome.COMPLETED;
    }

    private Outcome fail(Charge target, String failureCode, String failureMessage) {
        ChargeConfirmResult result = chargeTransaction.failApproval(
                target.getMemberId(), target.getChargeNo(), failureCode, failureMessage);
        if (!ChargeStatus.FAILED.name().equals(result.status())) {
            return alreadyHandled(target, result);
        }
        log.info("복구가 토스에 확인해 충전 건을 실패로 바꿨습니다. chargeNo={}, failureCode={}", target.getChargeNo(), failureCode);
        return Outcome.FAILED;
    }

    // 맡기는 다음 확인 시각만 미루므로, 토스 조회를 기다리는 사이 늦게 온 승인 응답이 먼저 끝낼 수 있다.
    // 그때 완료·실패 처리는 오류 없이 현재 상태를 돌려주므로, 복구가 끝냈다고 세거나 로그를 남기지 않는다
    private Outcome alreadyHandled(Charge target, ChargeConfirmResult result) {
        log.info("복구가 처리하려던 충전 건이 이미 끝나 있어 바꾸지 않았습니다. chargeNo={}, status={}",
                target.getChargeNo(), result.status());
        return Outcome.ALREADY_HANDLED;
    }

    private Outcome hold(Charge target, String reason) {
        chargeTransaction.holdForReview(target.getChargeNo());
        log.error("{} chargeNo={}, reason={}", HOLD, target.getChargeNo(), reason);
        return Outcome.HELD;
    }

    // 아직 알 수 없으면 맡을 때 미뤄 둔 확인 시각에 다시 묻는다. 상한까지 물었으면 멈춘다
    private Outcome retryOrHold(Charge target, int checkCount, String reason) {
        if (checkCount >= MAX_CHECK_COUNT) {
            return hold(target, "확인 " + checkCount + "번째에도 끝나지 않음: " + reason);
        }
        log.info("토스 결제 상태를 아직 알 수 없어 다음에 다시 확인합니다. chargeNo={}, checkCount={}, reason={}",
                target.getChargeNo(), checkCount, reason);
        return Outcome.RETRIED;
    }

    private enum Outcome {
        COMPLETED, FAILED, HELD, RETRIED, ALREADY_HANDLED
    }
}
