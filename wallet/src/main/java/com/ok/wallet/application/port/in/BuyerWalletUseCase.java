package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.BuyerBalanceResult;

/**
 * 구매자 지갑 잔액 조회 유스케이스.
 */
public interface BuyerWalletUseCase {

    /**
     * 현재 잔액을 잠그지 않고 조회한다. 지갑이 아직 없으면 0으로 본다.
     * 잔액을 바꾸지 않는 확인용이며, 잔액 변경 전 확인은 잠금 조회 후에 다시 해야 한다.
     */
    long getBalance(Long memberId);

    /**
     * 현재 잔액과 잔액 보유 한도를 함께 조회한다. 지갑이 아직 없으면 잔액은 0으로 본다.
     * 프론트가 충전 가능 금액(한도 - 잔액)을 계산할 수 있도록 한도를 같이 돌려준다.
     */
    BuyerBalanceResult getBalanceSummary(Long memberId);
}
