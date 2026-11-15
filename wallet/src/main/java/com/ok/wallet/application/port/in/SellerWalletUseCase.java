package com.ok.wallet.application.port.in;

/**
 * 판매자 지갑 정산금 입금, 잔액 조회 유스케이스.
 */
public interface SellerWalletUseCase {

    /**
     * 정산금을 판매자 지갑에 입금하고 원장에 기록한다.
     * 같은 정산(payoutId)으로 이미 입금했다면 아무것도 하지 않는다.
     * @param sellerId 정산을 받는 판매자의 회원 ID
     */
    void depositPayout(Long sellerId, Long payoutId, long amount);

    /**
     * 판매자 지갑의 현재 잔액을 잠그지 않고 조회한다. 지갑이 아직 없으면 0으로 본다.
     * 판매자 지갑은 첫 정산금 입금 때 만들어지므로, 그 전에는 없다.
     * 판매자 여부는 호출하는 쪽(컨트롤러의 역할 검사)이 보장한다.
     */
    long getBalance(Long memberId);
}
