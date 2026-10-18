package com.ok.wallet.service;

import com.ok.common.exception.RestApiException;
import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.domain.Charge;
import com.ok.wallet.domain.ChargeStatus;
import com.ok.wallet.exception.WalletErrorCode;
import com.ok.wallet.repository.ChargeRepository;
import com.ok.wallet.service.dto.ChargeConfirmResult;
import com.ok.wallet.service.dto.ChargeRequestResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 예치금 충전 요청을 담당한다. 토스 결제창을 열기 전에 충전 건을 대기 상태로 만들어,
 * 승인 시점에 클라이언트가 보낸 금액을 대조할 기준을 서버에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class ChargeService {
    private final ChargeRepository chargeRepository;
    private final TermsService termsService;
    private final BuyerWalletService buyerWalletService;
    private final Clock clock;

    /**
     * 충전을 요청한다. 필수 약관 동의 → 금액 규칙 → 잔액 한도 순서로 확인한다.
     * 동의는 프론트가 충전 버튼을 누를 때 약관 조회 API로 먼저 받고, 여기서는 건너뛴 경우나 그 사이 개정된 경우를 막는다.
     * 잔액 한도는 여기서 잠그지 않고 1차로 확인하며, 정확한 확인은 승인 시점에 지갑을 잠근 뒤 처리 중 충전까지 합쳐서 한다.
     */
    @Transactional
    public ChargeRequestResult request(Long memberId, long amount) {
        termsService.validateRequiredAgreed(memberId);
        Charge charge = Charge.request(memberId, amount);
        validateMaxBalance(memberId, amount);

        chargeRepository.save(charge);
        return ChargeRequestResult.from(charge);
    }

    private void validateMaxBalance(Long memberId, long amount) {
        if (BuyerWallet.exceedsMaxBalance(buyerWalletService.getBalance(memberId), amount)) {
            throw new RestApiException(WalletErrorCode.BALANCE_LIMIT_EXCEEDED);
        }
    }

    /**
     * 토스 결제창에서 실패한 충전 건을 실패로 바꾼다.
     * 대기 중인 건만 조건부 UPDATE로 바꿔, 같은 건의 승인과 겹쳐도 처리 중이 된 건을 실패로 덮어쓰지 않는다.
     */
    @Transactional
    public ChargeConfirmResult fail(Long memberId, String chargeNo, String failureCode, String failureMessage) {
        findOwnedCharge(memberId, chargeNo);
        chargeRepository.markFailedIfPending(chargeNo, failureCode, failureMessage, LocalDateTime.now(clock));
        // 조건부 UPDATE가 영속성 컨텍스트를 비우므로 다시 읽어 현재 상태를 돌려준다
        return ChargeConfirmResult.from(findOwnedCharge(memberId, chargeNo));
    }

    // 타인의 충전 건은 존재 여부도 알리지 않도록 없는 것과 같게 응답한다
    private Charge findOwnedCharge(Long memberId, String chargeNo) {
        return chargeRepository.findByChargeNo(chargeNo)
                .filter(charge -> charge.isOwnedBy(memberId))
                .orElseThrow(() -> new RestApiException(WalletErrorCode.CHARGE_NOT_FOUND));
    }
}
