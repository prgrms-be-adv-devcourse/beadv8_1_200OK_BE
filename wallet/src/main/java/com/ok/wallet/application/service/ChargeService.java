package com.ok.wallet.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.wallet.application.dto.ChargeConfirmResult;
import com.ok.wallet.application.dto.ChargeRequestResult;
import com.ok.wallet.application.port.in.ChargeUseCase;
import com.ok.wallet.application.port.out.ChargeRepository;
import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.domain.Charge;
import com.ok.wallet.domain.ChargeStatus;
import com.ok.wallet.exception.WalletErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예치금 충전 요청을 담당한다. 토스 결제창을 열기 전에 충전 건을 대기 상태로 만들어,
 * 승인 시점에 클라이언트가 보낸 금액을 대조할 기준을 서버에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class ChargeService implements ChargeUseCase {
    private final ChargeRepository chargeRepository;
    private final TermsService termsService;
    private final BuyerWalletService buyerWalletService;
    private final ChargeTransactionService chargeTransactionService;

    @Override
    @Transactional
    public ChargeRequestResult request(Long memberId, long amount) {
        termsService.validateRequiredAgreed(memberId);
        Charge charge = Charge.request(memberId, amount);
        validateMaxBalance(memberId, amount);

        chargeRepository.save(charge);
        return ChargeRequestResult.from(charge);
    }

    // 처리 중 충전도 곧 잔액이 될 수 있어 함께 더한다. 정확한 확인은 승인 시점에 지갑을 잠근 뒤 다시 한다.
    private void validateMaxBalance(Long memberId, long amount) {
        long balance = buyerWalletService.getBalance(memberId);
        long inProgressAmount = chargeRepository.sumAmountByMemberIdAndStatus(memberId, ChargeStatus.IN_PROGRESS);
        if (BuyerWallet.exceedsMaxBalance(balance + inProgressAmount, amount)) {
            throw new RestApiException(WalletErrorCode.BALANCE_LIMIT_EXCEEDED);
        }
    }

    // 트랜잭션과 조건부 UPDATE는 ChargeTransactionService.failPending이 맡는다.
    @Override
    public ChargeConfirmResult fail(Long memberId, String chargeNo, String failureCode, String failureMessage) {
        return chargeTransactionService.failPending(memberId, chargeNo, failureCode, failureMessage);
    }

    @Override
    @Transactional(readOnly = true)
    public ChargeConfirmResult getStatus(Long memberId, String chargeNo) {
        Charge charge = chargeRepository.findByChargeNo(chargeNo)
                .filter(found -> found.isOwnedBy(memberId))
                .orElseThrow(() -> new RestApiException(WalletErrorCode.CHARGE_NOT_FOUND));
        return ChargeConfirmResult.from(charge);
    }
}
