package com.ok.wallet.application.port.in;

import com.ok.wallet.domain.SellerWallet;

/**
 * 판매자 지갑 생성, 정산금 입금, 잔액 조회 유스케이스.
 */
public interface SellerWalletUseCase {

    /**
     * 판매자 지갑을 잠금 조회로 반환한다. 지갑이 없다면 생성한 뒤 반환한다.
     * 존재 확인은 일반 조회로 하고, 없으면 저장한 뒤 잠금 조회로 반환한다.
     * 반환된 지갑은 호출한 트랜잭션이 끝날 때까지 잠겨 있으므로 잔액 조회처럼 읽기만 하는 곳에는 쓰지 않는다.
     * 승인 리스너처럼 지갑이 있는지만 보장하면 되는 짧은 트랜잭션에서는, 생성 경로를 하나로 두기 위해 이 메서드를 쓴다.
     *
     * <p>판매자 승인 여부는 확인하지 않는다. 정산금 입금처럼 승인된 판매자임이 이미 보장된 경로에서만 호출한다.
     */
    SellerWallet getOrCreate(Long memberId);

    /**
     * 정산금을 판매자 지갑에 입금하고 원장에 기록한다.
     * 같은 정산(payoutId)으로 이미 입금했다면 아무것도 하지 않는다.
     * @param sellerId 정산을 받는 판매자의 회원 ID
     */
    void depositPayout(Long sellerId, Long payoutId, long amount);

    /**
     * 판매자 지갑의 현재 잔액을 잠그지 않고 조회한다. 지갑이 아직 없으면 0으로 본다.
     * 판매자 지갑은 판매자 승인 이벤트로 비동기로 만들어져, 승인 직후에는 아직 없을 수 있다.
     * 판매자 여부는 호출하는 쪽(컨트롤러의 역할 검사)이 보장한다.
     */
    long getBalance(Long memberId);
}
