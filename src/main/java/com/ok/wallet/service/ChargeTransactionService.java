package com.ok.wallet.service;

import com.ok.common.exception.RestApiException;
import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.domain.Charge;
import com.ok.wallet.domain.ChargeStatus;
import com.ok.wallet.exception.WalletErrorCode;
import com.ok.wallet.repository.ChargeRepository;
import com.ok.wallet.service.dto.ChargeConfirmResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 충전 승인과 결제 실패의 트랜잭션 단계. 토스 호출을 트랜잭션 밖에 두려고 ChargeConfirmService와 빈을 나눴다
 * (같은 빈 안에서 호출하면 프록시를 거치지 않아 @Transactional이 적용되지 않는다).
 * 충전 건을 바꾸는 단계는 READ COMMITTED로 실행해, 지갑 잠금을 기다리는 동안 다른 요청이 커밋한
 * 처리 중 충전과 상태 변화를 최신으로 읽는다 (REPEATABLE READ의 스냅샷은 잠금 전 시점에 고정된다).
 */
@Service
@RequiredArgsConstructor
class ChargeTransactionService {
    private final ChargeRepository chargeRepository;
    private final BuyerWalletService buyerWalletService;
    private final Clock clock;

    /**
     * [트랜잭션 1] 승인을 시작할 수 있는지 확인하고 충전 건을 처리 중으로 바꾼다.
     * 이미 처리 중이거나 끝난 건이면 토스를 호출하지 않도록 현재 상태를 돌려준다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConfirmStart startConfirm(Long memberId, String chargeNo, String paymentKey, long amount) {
        Charge charge = findOwnedCharge(memberId, chargeNo);
        if (!charge.hasAmount(amount)) {
            throw new RestApiException(WalletErrorCode.CHARGE_AMOUNT_MISMATCH);
        }
        if (charge.getStatus() != ChargeStatus.PENDING) {
            return ConfirmStart.alreadyHandled(charge);
        }

        // 지갑을 잠가 같은 회원의 승인을 한 줄로 세운 뒤, 처리 중 충전까지 합쳐 한도를 다시 확인한다.
        // READ COMMITTED라 잠금을 기다리는 사이 다른 승인이 커밋한 처리 중 충전도 합계에 들어간다
        BuyerWallet wallet = buyerWalletService.getOrCreate(memberId);
        long inProgressAmount = chargeRepository.sumAmountByMemberIdAndStatus(memberId, ChargeStatus.IN_PROGRESS);
        if (BuyerWallet.exceedsMaxBalance(wallet.getBalance() + inProgressAmount, amount)) {
            throw new RestApiException(WalletErrorCode.BALANCE_LIMIT_EXCEEDED);
        }

        // 같은 건으로 승인이 동시에 오면 한 요청만 1을 받는다. 0이면 다른 요청이 커밋한 최신 상태를 돌려준다
        if (chargeRepository.markInProgress(chargeNo, paymentKey, LocalDateTime.now(clock)) == 0) {
            return ConfirmStart.alreadyHandled(findOwnedCharge(memberId, chargeNo));
        }
        return ConfirmStart.begin();
    }

    /** [트랜잭션 2] 승인된 결제를 잔액에 반영하고 충전 건을 완료한다. */
    @Transactional
    public ChargeConfirmResult complete(Long memberId, String chargeNo, String paymentKey) {
        Charge charge = findOwnedCharge(memberId, chargeNo);
        // 복구 스케줄러가 먼저 처리했다면 다시 반영하지 않는다
        if (charge.getStatus() != ChargeStatus.IN_PROGRESS) {
            return ChargeConfirmResult.from(charge);
        }
        // 지갑 잠금 → 근거 키 확인 → 잔액 증가·원장 기록. 같은 결제는 근거 키로 한 번만 반영된다
        buyerWalletService.chargeByPayment(memberId, paymentKey, charge.getAmount());
        charge.complete(paymentKey, LocalDateTime.now(clock));
        return ChargeConfirmResult.from(charge);
    }

    /** [트랜잭션 2] 토스가 명확히 거절한 충전 건을 실패로 기록한다. */
    @Transactional
    public ChargeConfirmResult failApproval(Long memberId, String chargeNo, String failureCode, String failureMessage) {
        Charge charge = findOwnedCharge(memberId, chargeNo);
        if (charge.getStatus() == ChargeStatus.IN_PROGRESS) {
            charge.fail(failureCode, failureMessage, LocalDateTime.now(clock));
        }
        return ChargeConfirmResult.from(charge);
    }

    /**
     * 결제창에서 실패한 충전 건을 실패로 바꾼다.
     * 대기 중인 건만 조건부 UPDATE로 바꿔, 같은 건의 승인과 겹쳐도 처리 중이 된 건을 실패로 덮어쓰지 않는다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChargeConfirmResult failPending(Long memberId, String chargeNo, String failureCode, String failureMessage) {
        findOwnedCharge(memberId, chargeNo);
        chargeRepository.markFailedIfPending(chargeNo, failureCode, failureMessage, LocalDateTime.now(clock));
        // 바꿨으면 실패 상태를, 0행이면 다른 요청이 커밋한 최신 상태를 돌려준다
        return ChargeConfirmResult.from(findOwnedCharge(memberId, chargeNo));
    }

    @Transactional(readOnly = true)
    public ChargeConfirmResult currentStatus(Long memberId, String chargeNo) {
        return ChargeConfirmResult.from(findOwnedCharge(memberId, chargeNo));
    }

    // 타인의 충전 건은 존재 여부도 알리지 않도록 없는 것과 같게 응답한다
    private Charge findOwnedCharge(Long memberId, String chargeNo) {
        return chargeRepository.findByChargeNo(chargeNo)
                .filter(charge -> charge.isOwnedBy(memberId))
                .orElseThrow(() -> new RestApiException(WalletErrorCode.CHARGE_NOT_FOUND));
    }

    /** 트랜잭션 1의 결과. 이 요청이 승인을 시작했는지, 이미 처리된 건이었는지를 담는다. */
    record ConfirmStart(boolean started, ChargeConfirmResult current) {
        // 이 요청이 처리 중으로 바꿔 토스 승인을 진행해야 함
        static ConfirmStart begin() {
            return new ConfirmStart(true, null);
        }

        // 이미 다른 요청이 시작했거나 끝난 건이라 현재 상태만 돌려줌
        static ConfirmStart alreadyHandled(Charge charge) {
            return new ConfirmStart(false, ChargeConfirmResult.from(charge));
        }
    }
}
