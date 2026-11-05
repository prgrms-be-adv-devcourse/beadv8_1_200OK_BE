package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.BuyerBalanceResult;
import com.ok.wallet.domain.BuyerWallet;

/**
 * 구매자 지갑 생성과 잔액 조회 유스케이스.
 */
public interface BuyerWalletUseCase {

    /**
     * 구매자 지갑을 잠금 조회로 반환한다. 지갑이 없다면 생성한 뒤 반환한다.
     * 존재 확인은 일반 조회로 하고, 없으면 저장한 뒤 잠금 조회로 반환한다.
     * 반환된 지갑은 호출한 트랜잭션이 끝날 때까지 잠겨 있으므로 잔액 조회처럼 읽기만 하는 곳에는 쓰지 않는다.
     * 회원가입 리스너처럼 지갑이 있는지만 보장하면 되는 짧은 트랜잭션에서는, 생성 경로를 하나로 두기 위해 이 메서드를 쓴다.
     */
    BuyerWallet getOrCreate(Long memberId);

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
