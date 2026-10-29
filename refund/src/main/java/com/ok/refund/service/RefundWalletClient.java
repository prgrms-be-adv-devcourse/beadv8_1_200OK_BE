package com.ok.refund.service;

/**
 * 지갑 모듈을 부른다. 1차 구현은 HTTP 동기 호출이고, 지갑 팀 회신 전까지 경로·응답 모양은 요청서(설계 10장) 기준 가정이다.
 */
public interface RefundWalletClient {

    /**
     * 구매자에게 돈을 돌려준다(흐름 6번). 지갑은 근거 키 {@code REFUND:{refundId}}로 중복을 막는다.
     *
     * @param sellerId 기록용. 1단계에서는 판매자 지갑을 쓰지 않는다
     * @param amount   승인 때 저장한 돌려줄 금액 (원). 다시 보낼 때도 같은 값을 보내야 지갑 중복 확인과 맞는다
     * @throws com.ok.common.exception.RestApiException 호출 실패면 {@code WALLET_API_FAILED}
     */
    void refund(Long refundId, Long orderId, Long buyerId, Long sellerId, long amount);
}
