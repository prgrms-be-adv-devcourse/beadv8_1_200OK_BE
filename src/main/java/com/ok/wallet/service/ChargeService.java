package com.ok.wallet.service;

import com.ok.common.exception.RestApiException;
import com.ok.wallet.domain.BuyerWallet;
import com.ok.wallet.domain.Charge;
import com.ok.wallet.exception.WalletErrorCode;
import com.ok.wallet.repository.ChargeRepository;
import com.ok.wallet.service.dto.ChargeRequestResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
