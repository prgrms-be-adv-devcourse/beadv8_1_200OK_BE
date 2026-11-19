package com.ok.wallet.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.wallet.application.dto.ChargeAdminDetailResult;
import com.ok.wallet.application.dto.ChargeAdminResult;
import com.ok.wallet.application.dto.ChargeConfirmResult;
import com.ok.wallet.application.dto.TossCancelResult;
import com.ok.wallet.application.dto.TossPaymentLookupResult;
import com.ok.wallet.application.dto.TossPaymentStatus;
import com.ok.wallet.application.port.in.ChargeAdminUseCase;
import com.ok.wallet.application.port.out.TossPaymentsClient;
import com.ok.wallet.domain.Charge;
import com.ok.wallet.domain.ChargeStatus;
import com.ok.wallet.exception.WalletErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 복구가 끝내지 못한 충전 건을 관리자가 처리한다. 이 클래스에는 @Transactional을 두지 않는다.
 * 승인 흐름과 같은 이유로 토스 호출은 트랜잭션 밖에서 하고, DB 반영은 ChargeTransactionService가 충전 건을 잠근 뒤 한다.
 *
 * <p>무엇을 할지는 관리자가 고르지만, 처리 직전에 토스에 다시 물어 상태와 맞지 않는 선택은 막는다.
 * 토스가 승인한 결제를 실패로 끝내면 돈은 빠졌는데 잔액이 늘지 않고, 승인되지 않은 결제를 반영하면 돈 없이 잔액이 는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeAdminService implements ChargeAdminUseCase {
    // 처리할 건은 복구가 멈춘 드문 건이라 한 번에 이만큼이면 충분하다
    private static final int TARGET_LIMIT = 100;
    private static final String ADMIN_FAILURE_CODE = "ADMIN_FAILED";
    private static final String CANCEL_IDEMPOTENCY_KEY_PREFIX = "CANCEL-";
    // 관리자가 실패로 둘 수 있는 토스 상태. 승인 전 단계(준비, 인증됨)는 아직 승인되거나 만료될 수 있어 기다린다.
    // 취소된 결제는 이미 환불된 것이라 실패로 둬도 된다
    private static final Set<TossPaymentStatus> FAILABLE_STATUSES =
            EnumSet.of(TossPaymentStatus.ABORTED, TossPaymentStatus.EXPIRED, TossPaymentStatus.CANCELED);

    private final ChargeTransactionService chargeTransaction;
    private final TossPaymentsClient tossPaymentsClient;
    private final Clock clock;

    @Override
    public List<ChargeAdminResult> findTargets() {
        return chargeTransaction.findAdminTargets(TARGET_LIMIT).stream()
                .map(ChargeAdminResult::from)
                .toList();
    }

    @Override
    public ChargeAdminDetailResult findDetail(String chargeNo) {
        Charge charge = chargeTransaction.findCharge(chargeNo);
        return ChargeAdminDetailResult.of(ChargeAdminResult.from(charge), lookup(chargeNo));
    }

    @Override
    public ChargeAdminResult complete(Long adminId, String chargeNo) {
        Charge charge = chargeTransaction.findCharge(chargeNo);
        if (!charge.isHeldForReview()) {
            throw new RestApiException(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE, "잔액 반영은 복구가 멈춘 처리 중 건만 할 수 있습니다.");
        }
        requireNoCancelRequest(charge);
        TossPaymentLookupResult.Found found = requireFound(lookup(chargeNo));
        if (found.status() != TossPaymentStatus.DONE || !charge.hasAmount(found.totalAmount())) {
            throw mismatch(found, "토스가 같은 금액으로 승인한 결제만 잔액에 반영할 수 있습니다.");
        }
        // 토스 조회는 승인된 시도가 있으면 그 시도를 돌려주므로, 그 결제 키가 잔액 반영 근거가 된다
        ChargeConfirmResult result = chargeTransaction.complete(charge.getMemberId(), chargeNo, found.paymentKey());
        requireStatus(chargeNo, result.status(), ChargeStatus.COMPLETED);
        log.info("관리자가 충전 건을 잔액에 반영했습니다. adminId={}, chargeNo={}", adminId, chargeNo);
        return ChargeAdminResult.from(chargeTransaction.findCharge(chargeNo));
    }

    @Override
    public ChargeAdminResult fail(Long adminId, String chargeNo, String reason) {
        Charge charge = chargeTransaction.findCharge(chargeNo);
        if (!charge.isHeldForReview()) {
            throw new RestApiException(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE, "실패 처리는 복구가 멈춘 처리 중 건만 할 수 있습니다.");
        }
        requireNoCancelRequest(charge);
        TossPaymentLookupResult lookup = lookup(chargeNo);
        if (lookup instanceof TossPaymentLookupResult.Unknown) {
            throw new RestApiException(WalletErrorCode.CHARGE_TOSS_STATUS_UNKNOWN);
        }
        if (lookup instanceof TossPaymentLookupResult.Found found && !FAILABLE_STATUSES.contains(found.status())) {
            throw mismatch(found, "토스 결제가 승인됐거나 아직 진행 중이라 실패로 둘 수 없습니다.");
        }
        ChargeConfirmResult result = chargeTransaction.failApproval(charge.getMemberId(), chargeNo, ADMIN_FAILURE_CODE, reason);
        requireStatus(chargeNo, result.status(), ChargeStatus.FAILED);
        log.info("관리자가 충전 건을 실패로 바꿨습니다. adminId={}, chargeNo={}", adminId, chargeNo);
        return ChargeAdminResult.from(chargeTransaction.findCharge(chargeNo));
    }

    @Override
    public ChargeAdminResult cancelPayment(Long adminId, String chargeNo, String reason) {
        Charge charge = chargeTransaction.findCharge(chargeNo);
        if (!charge.isHeldForReview() && !charge.isAwaitingPaymentCancel()) {
            throw new RestApiException(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE,
                    "결제 취소는 복구가 멈춘 처리 중 건이나 승인 뒤 실패한 건만 할 수 있습니다.");
        }
        TossPaymentLookupResult.Found found = requireFound(lookup(chargeNo));
        // 앞선 시도에서 토스 취소는 됐는데 기록하지 못했거나, 토스 상점관리자에서 취소한 경우다
        if (found.status() == TossPaymentStatus.CANCELED) {
            return recordPaymentCanceled(adminId, chargeNo, reason);
        }
        if (found.status() != TossPaymentStatus.DONE || !charge.hasAmount(found.totalAmount())) {
            throw mismatch(found, "토스가 같은 금액으로 승인한 결제만 전액 취소할 수 있습니다.");
        }
        // 처리 중 건은 토스를 부르기 전에 잠그고 취소 요청을 남긴다. 토스 취소를 기다리는 사이 다른 관리자의 반영이나
        // 늦게 온 승인 응답이 완료하면 환불과 잔액 증가가 함께 일어나므로, 표시가 있는 건은 완료·실패로 바꾸지 않는다
        if (charge.isHeldForReview()) {
            chargeTransaction.requestPaymentCancel(chargeNo);
        }
        TossCancelResult cancel = cancel(found.paymentKey(), chargeNo, reason);
        return switch (cancel) {
            case TossCancelResult.Canceled canceled -> recordPaymentCanceled(adminId, chargeNo, reason);
            case TossCancelResult.Rejected rejected -> handleRejected(adminId, chargeNo, reason, rejected);
            // 응답만으로는 취소됐는지 확실하지 않아, 토스에 다시 물어 전액 취소된 것을 확인한 뒤에만 기록한다.
            // 확인하지 못하면 환불됐을 수도 있어 표시를 남겨 두고, 결제 취소만 다시 할 수 있게 한다
            case TossCancelResult.AlreadyCanceled alreadyCanceled -> recordIfCanceled(adminId, chargeNo, reason);
            case TossCancelResult.Unknown unknown -> recordIfCanceled(adminId, chargeNo, reason);
        };
    }

    // 조회처럼 예상하지 못한 예외도 취소 결과를 알 수 없는 것으로 본다. 다시 조회해 취소됐을 때만 기록하고, 아니면 표시를 남긴다
    private TossCancelResult cancel(String paymentKey, String chargeNo, String reason) {
        try {
            return tossPaymentsClient.cancel(paymentKey, chargeNo, reason, idempotencyKey(chargeNo));
        } catch (RuntimeException e) {
            log.warn("관리자 결제 취소 중 토스 호출에서 예상하지 못한 오류가 났습니다. chargeNo={}", chargeNo, e);
            return new TossCancelResult.Unknown("토스 결제 취소 중 예상하지 못한 오류");
        }
    }

    // 거절은 이번 요청이 취소되지 않았다는 뜻일 뿐이다. 앞서 결과를 모른 취소나 다른 관리자의 취소가 토스에서 끝났을 수 있어
    // 다시 묻고, 취소됐으면 기록한다. 취소 요청 표시는 남긴다(지우는 절차는 개발 문서의 충전 관리자 처리)
    private ChargeAdminResult handleRejected(Long adminId, String chargeNo, String reason, TossCancelResult.Rejected rejected) {
        if (lookup(chargeNo) instanceof TossPaymentLookupResult.Found found && found.status() == TossPaymentStatus.CANCELED) {
            return recordPaymentCanceled(adminId, chargeNo, reason);
        }
        throw new RestApiException(WalletErrorCode.CHARGE_PAYMENT_CANCEL_REJECTED,
                "토스가 결제 취소를 거절했습니다. code=" + rejected.code() + ", message=" + rejected.message());
    }

    private ChargeAdminResult recordIfCanceled(Long adminId, String chargeNo, String reason) {
        TossPaymentLookupResult.Found found = requireFound(lookup(chargeNo));
        if (found.status() != TossPaymentStatus.CANCELED) {
            // 아직 승인 상태면 취소되지 않은 것이다. 시도마다 새 멱등 키를 쓰므로 다시 시도할 수 있다
            throw new RestApiException(WalletErrorCode.CHARGE_TOSS_STATUS_UNKNOWN,
                    "토스 결제 취소를 확인하지 못했습니다. 토스 결제 상태: " + found.status() + ". 잠시 후 다시 시도해 주세요.");
        }
        return recordPaymentCanceled(adminId, chargeNo, reason);
    }

    private ChargeAdminResult recordPaymentCanceled(Long adminId, String chargeNo, String reason) {
        ChargeAdminResult result = chargeTransaction.recordPaymentCanceled(chargeNo, reason);
        if (!ChargeStatus.FAILED.name().equals(result.status())) {
            throw new RestApiException(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE,
                    "토스 결제는 취소됐지만 충전 건이 이미 완료되어 있습니다. 잔액을 확인해 주세요.");
        }
        log.info("관리자가 토스 결제를 취소했습니다. adminId={}, chargeNo={}", adminId, chargeNo);
        return result;
    }

    // 같은 키로 다시 보내면 토스가 첫 응답(일시 오류 포함)을 그대로 돌려주므로 시도마다 새로 만든다.
    // 전액 취소는 토스에서 한 번만 되므로 키가 달라도 두 번 취소되지 않는다
    private String idempotencyKey(String chargeNo) {
        return CANCEL_IDEMPOTENCY_KEY_PREFIX + chargeNo + "-" + clock.millis();
    }

    // 조회 포트가 HTTP 오류는 분류해 주지만, 그 밖의 예상하지 못한 예외도 결제 상태를 알 수 없는 것으로 본다
    private TossPaymentLookupResult lookup(String chargeNo) {
        try {
            return tossPaymentsClient.findPayment(chargeNo);
        } catch (RuntimeException e) {
            log.warn("관리자 처리 중 토스 결제 조회에서 예상하지 못한 오류가 났습니다. chargeNo={}", chargeNo, e);
            return new TossPaymentLookupResult.Unknown("토스 결제 조회 중 예상하지 못한 오류");
        }
    }

    private static TossPaymentLookupResult.Found requireFound(TossPaymentLookupResult lookup) {
        return switch (lookup) {
            case TossPaymentLookupResult.Found found -> found;
            case TossPaymentLookupResult.NotFound notFound -> throw new RestApiException(
                    WalletErrorCode.CHARGE_TOSS_STATUS_MISMATCH, "토스에 결제가 없습니다.");
            case TossPaymentLookupResult.Unknown unknown -> throw new RestApiException(WalletErrorCode.CHARGE_TOSS_STATUS_UNKNOWN);
        };
    }

    private static RestApiException mismatch(TossPaymentLookupResult.Found found, String reason) {
        return new RestApiException(WalletErrorCode.CHARGE_TOSS_STATUS_MISMATCH,
                reason + " 토스 결제 상태: " + found.status() + ", 금액: " + found.totalAmount());
    }

    // 토스에 묻는 사이 늦게 온 승인 응답이 먼저 끝내거나 다른 관리자가 결제 취소를 요청할 수 있다.
    // 그때 완료·실패 처리는 바꾸지 않고 현재 상태를 그대로 돌려준다
    private void requireStatus(String chargeNo, String actual, ChargeStatus expected) {
        if (expected.name().equals(actual)) {
            return;
        }
        requireNoCancelRequest(chargeTransaction.findCharge(chargeNo));
        throw stateChanged(actual);
    }

    private static void requireNoCancelRequest(Charge charge) {
        if (charge.isPaymentCancelRequested()) {
            throw new RestApiException(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE,
                    "결제 취소를 요청한 건입니다. 취소 결과를 확인하지 못했다면 결제 취소를 다시 시도해 주세요.");
        }
    }

    private static RestApiException stateChanged(String actual) {
        return new RestApiException(WalletErrorCode.CHARGE_NOT_ADMIN_HANDLEABLE,
                "처리하는 사이 충전 건 상태가 바뀌었습니다. 현재 상태: " + actual);
    }
}
