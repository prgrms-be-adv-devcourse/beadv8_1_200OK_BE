package com.ok.wallet.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.wallet.application.dto.ChargeConfirmResult;
import com.ok.wallet.application.port.out.ChargeRepository;
import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.domain.Charge;
import com.ok.wallet.domain.ChargeStatus;
import com.ok.wallet.exception.WalletErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 충전 승인과 결제 실패의 트랜잭션 단계. 토스 호출을 트랜잭션 밖에 두려고 ChargeConfirmService와 빈을 나눴다
 * (같은 빈 안에서 호출하면 프록시를 거치지 않아 @Transactional이 적용되지 않는다).
 * 충전 건을 바꾸는 단계는 READ COMMITTED로 실행해, 지갑 잠금을 기다리는 동안 다른 요청이 커밋한
 * 처리 중 충전과 상태 변화를 최신으로 읽는다 (REPEATABLE READ의 스냅샷은 잠금 전 시점에 고정된다).
 */
@Service
@RequiredArgsConstructor
@Slf4j
class ChargeTransactionService {
    // 승인 호출은 토스 응답 대기(30초) 안에 끝나므로, 그보다 넉넉히 뒤에 복구가 처음 확인해 진행 중인 승인과 겹치지 않게 한다
    private static final Duration FIRST_RECOVERY_CHECK_DELAY = Duration.ofMinutes(5);
    // 확인 상한(10번)을 다 써도 토스 승인 가능 시간(결제 없음을 실패로 보는 1시간)을 넘겨 확인하도록 간격을 둔다
    private static final Duration RECOVERY_RECHECK_DELAY = Duration.ofMinutes(10);

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
        if (markInProgress(chargeNo, paymentKey) == 0) {
            return ConfirmStart.alreadyHandled(findOwnedCharge(memberId, chargeNo));
        }
        return ConfirmStart.begin();
    }

    // 결제 키는 클라이언트가 보낸 값이라, 이미 다른 충전 건에 쓰였으면 유니크 제약에 걸린다.
    // 토스를 호출하기 전에 409로 막고, 예외로 트랜잭션이 롤백되어 충전 건은 대기 상태로 남는다
    private int markInProgress(String chargeNo, String paymentKey) {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            return chargeRepository.markInProgress(chargeNo, paymentKey, now, now.plus(FIRST_RECOVERY_CHECK_DELAY));
        } catch (DataIntegrityViolationException e) {
            throw new RestApiException(WalletErrorCode.PAYMENT_KEY_ALREADY_USED);
        }
    }

    /**
     * [트랜잭션 2] 승인된 결제를 잔액에 반영하고 충전 건을 완료한다.
     * 충전 건을 잠근 뒤 상태를 보므로, 복구가 같은 건을 동시에 끝내도 한쪽만 처리 중을 보고 반영한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChargeConfirmResult complete(Long memberId, String chargeNo, String paymentKey) {
        // 승인 시작(startConfirm)과 같은 순서로 지갑 → 충전 건을 잠근다. 순서가 반대면 같은 건의 승인 요청이
        // 겹칠 때 서로의 잠금을 기다리다 교착에 빠진다.
        // 잠그기 전에 충전 건을 읽지 않는다. 먼저 읽으면 잠금 조회가 그때 읽어 둔 옛 상태를 그대로 돌려준다
        buyerWalletService.getOrCreate(memberId);
        Charge charge = findOwnedChargeForUpdate(memberId, chargeNo);
        if (charge.getStatus() != ChargeStatus.IN_PROGRESS) {
            // 복구 스케줄러가 먼저 처리한 경우다. 이미 완료면 정상이지만, 실패로 처리된 건이면
            // 토스는 승인했는데 잔액에 반영되지 않은 상태라 환불 등 확인이 필요하다
            if (charge.getStatus() == ChargeStatus.FAILED) {
                log.error("[CHARGE_APPROVED_BUT_FAILED] 토스는 승인했지만 충전 건이 이미 실패로 처리되어 있습니다. chargeNo={}", chargeNo);
            }
            return ChargeConfirmResult.from(charge);
        }
        // 지갑 잠금 → 근거 키 확인 → 잔액 증가·원장 기록. 같은 결제는 근거 키로 한 번만 반영된다
        buyerWalletService.chargeByPayment(memberId, paymentKey, charge.getAmount());
        charge.complete(paymentKey, LocalDateTime.now(clock));
        return ChargeConfirmResult.from(charge);
    }

    /**
     * [트랜잭션 2] 토스가 명확히 거절한 충전 건을 실패로 기록한다.
     * 충전 건을 잠근 뒤 상태를 보므로, 복구가 먼저 완료한 건을 실패로 덮어쓰지 않는다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChargeConfirmResult failApproval(Long memberId, String chargeNo, String failureCode, String failureMessage) {
        Charge charge = findOwnedChargeForUpdate(memberId, chargeNo);
        if (charge.getStatus() == ChargeStatus.IN_PROGRESS) {
            // 토스가 준 거절 코드·메시지는 길이를 통제할 수 없어, 컬럼 길이에 맞춰 잘라 저장한다
            charge.fail(truncate(failureCode, 100), truncate(failureMessage, 500), LocalDateTime.now(clock));
        }
        return ChargeConfirmResult.from(charge);
    }

    /**
     * 결제창에서 실패한 충전 건을 실패로 바꾼다.
     * 대기 중인 건만 조건부 UPDATE로 바꿔, 같은 건의 승인과 겹쳐도 처리 중이 된 건을 실패로 덮어쓰지 않는다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChargeConfirmResult failPending(Long memberId, String chargeNo, String failureCode, String failureMessage) {
        // 소유자 확인을 위해 먼저 읽는다 (타인의 건이면 여기서 404)
        findOwnedCharge(memberId, chargeNo);
        chargeRepository.markFailedIfPending(chargeNo, failureCode, failureMessage, LocalDateTime.now(clock));
        // 조건부 UPDATE가 영속성 컨텍스트를 비우므로(clearAutomatically) 다시 읽어 결과 상태를 돌려준다.
        // 바꿨으면 실패 상태를, 0행이면 다른 요청이 커밋한 최신 상태를 돌려준다 (READ COMMITTED)
        return ChargeConfirmResult.from(findOwnedCharge(memberId, chargeNo));
    }

    /** 복구가 토스에 확인할 때가 된 처리 중 충전 건을 읽는다. */
    @Transactional(readOnly = true)
    public List<Charge> findDueForRecovery(int limit) {
        return chargeRepository.findDueForRecovery(LocalDateTime.now(clock), limit);
    }

    /**
     * 복구가 이 충전 건을 맡는다. 다음 확인 시각을 미루고 확인 횟수를 늘리며, 맡았으면 true다.
     * 확인 시각을 토스에 묻기 전에 미뤄 두므로, 반영 중 오류가 나도 다음 확인 때 다시 묻는다.
     */
    @Transactional
    public boolean claimForRecovery(String chargeNo) {
        LocalDateTime now = LocalDateTime.now(clock);
        return chargeRepository.claimForRecovery(chargeNo, now, now.plus(RECOVERY_RECHECK_DELAY)) == 1;
    }

    /** 이 시각 전에 요청됐는데 아직 처리 중인 충전 번호를 읽는다. */
    @Transactional(readOnly = true)
    public List<String> findInProgressChargeNosCreatedBefore(LocalDateTime createdBefore, int limit) {
        return chargeRepository.findInProgressChargeNosCreatedBefore(createdBefore, limit);
    }

    /** 처리 중인 충전 건을 복구가 더 고르지 않게 멈춘다. 사람이 토스 결제를 확인한 뒤 처리한다. */
    @Transactional
    public void holdForReview(String chargeNo) {
        chargeRepository.holdForReview(chargeNo, LocalDateTime.now(clock));
    }

    // 타인의 충전 건은 존재 여부도 알리지 않도록 없는 것과 같게 응답한다
    private Charge findOwnedCharge(Long memberId, String chargeNo) {
        return chargeRepository.findByChargeNo(chargeNo)
                .filter(charge -> charge.isOwnedBy(memberId))
                .orElseThrow(() -> new RestApiException(WalletErrorCode.CHARGE_NOT_FOUND));
    }

    private Charge findOwnedChargeForUpdate(Long memberId, String chargeNo) {
        return chargeRepository.findForUpdateByChargeNo(chargeNo)
                .filter(charge -> charge.isOwnedBy(memberId))
                .orElseThrow(() -> new RestApiException(WalletErrorCode.CHARGE_NOT_FOUND));
    }

    // 값이 최대 길이를 넘으면 앞부분만 남긴다. 사용자 요청은 입구(@Size)에서 막고, 외부 응답처럼 통제할 수 없는 값에만 쓴다
    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
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
