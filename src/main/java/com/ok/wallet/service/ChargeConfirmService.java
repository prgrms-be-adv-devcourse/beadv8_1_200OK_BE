package com.ok.wallet.service;

import com.ok.wallet.client.toss.TossConfirmResult;
import com.ok.wallet.client.toss.TossPaymentsClient;
import com.ok.wallet.domain.ChargeStatus;
import com.ok.wallet.service.dto.ChargeConfirmResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * 충전 승인 흐름을 조율한다. 이 클래스에는 @Transactional을 두지 않는다.
 * 토스 응답을 기다리는 동안 DB 커넥션을 잡고 있으면 토스가 느려질 때 커넥션 풀이 고갈되어
 * 결제와 무관한 API까지 멈추므로, 토스 호출은 두 트랜잭션 사이(트랜잭션 밖)에서 한다.
 *
 * <p>아래 로그 표시는 충전 건이 처리 중으로 남아 복구(#135)가 필요한 경우를 운영에서 찾기 위한 것이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeConfirmService {
    private static final String CONFIRM_UNKNOWN = "[CHARGE_CONFIRM_UNKNOWN] 토스 승인 호출 중 예상하지 못한 오류가 났습니다.";
    private static final String COMPLETE_FAILED = "[CHARGE_COMPLETE_FAILED] 승인된 결제의 잔액 반영에 실패했습니다.";
    private static final String FAIL_RECORD_FAILED = "[CHARGE_FAIL_RECORD_FAILED] 거절된 결제의 실패 기록에 실패했습니다.";

    private final ChargeTransactionService chargeTransaction;
    private final TossPaymentsClient tossPaymentsClient;

    public ChargeConfirmResult confirm(Long memberId, String chargeNo, String paymentKey, long amount) {
        // [트랜잭션 1] 소유자·금액·한도 확인 후 처리 중으로 전이
        ChargeTransactionService.ConfirmStart start =
                chargeTransaction.startConfirm(memberId, chargeNo, paymentKey, amount);
        if (!start.started()) {
            // 이미 다른 요청이 승인을 시작했거나 끝난 건: 토스를 다시 호출하지 않는다
            return start.current();
        }

        // [트랜잭션 밖] 토스 승인
        TossConfirmResult result = callToss(chargeNo, paymentKey, amount);

        // [트랜잭션 2] 결과 반영
        return switch (result) {
            case TossConfirmResult.Approved approved -> applyOrInProgress(chargeNo, amount, COMPLETE_FAILED,
                    () -> chargeTransaction.complete(memberId, chargeNo, approved.paymentKey()));
            case TossConfirmResult.Rejected rejected -> applyOrInProgress(chargeNo, amount, FAIL_RECORD_FAILED,
                    () -> chargeTransaction.failApproval(memberId, chargeNo, rejected.code(), rejected.message()));
            // 승인 여부를 알 수 없어 처리 중으로 두고, 복구 스케줄러(#135)가 토스에 확인한다
            case TossConfirmResult.Unknown unknown -> inProgress(chargeNo, amount);
        };
    }

    // 클라이언트가 HTTP 오류는 분류해 주지만, 그 밖의 예상하지 못한 예외도 승인 여부를 알 수 없으므로 불명확으로 처리한다
    private TossConfirmResult callToss(String chargeNo, String paymentKey, long amount) {
        try {
            return tossPaymentsClient.confirm(paymentKey, chargeNo, amount);
        } catch (RuntimeException e) {
            log.error("{} chargeNo={}", CONFIRM_UNKNOWN, chargeNo, e);
            return new TossConfirmResult.Unknown("토스 승인 호출 중 예상하지 못한 오류");
        }
    }

    // 토스 결과를 DB에 반영하다 실패하면 충전 건은 처리 중으로 남는다.
    // 복구 대상임을 로그로 남기고, 사용자가 실패로 오해해 다시 결제하지 않도록 처리 중으로 응답한다
    private ChargeConfirmResult applyOrInProgress(String chargeNo, long amount, String failureLog,
                                                  Supplier<ChargeConfirmResult> apply) {
        try {
            return apply.get();
        } catch (RuntimeException e) {
            log.error("{} chargeNo={}", failureLog, chargeNo, e);
            return inProgress(chargeNo, amount);
        }
    }

    // DB에 다시 묻지 않고 처리 중 응답을 만든다 (반영 실패 원인이 DB일 수 있어서)
    private static ChargeConfirmResult inProgress(String chargeNo, long amount) {
        return new ChargeConfirmResult(chargeNo, ChargeStatus.IN_PROGRESS.name(), amount);
    }
}