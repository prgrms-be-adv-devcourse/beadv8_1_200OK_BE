package com.ok.refund.application.port.out;

/**
 * 지갑에 보내는 예치금 반환 요청 값. 메시지 모양(토픽·JSON 필드 이름)은 어댑터가 정하고, 서비스는 이 값만 넘긴다.
 *
 * @param refundId 지갑이 같은 요청을 두 번 받아도 돈을 한 번만 주도록 중복을 가르는 값 (#413 약속)
 * @param buyerId  돈을 돌려받을 구매자
 * @param sellerId 상품을 판 판매자. 지갑이 판매자 쪽 장부를 맞출 때 쓴다
 * @param amount   돌려줄 금액 (원). 상품 금액 + 돌려줄 배송비 + 도서산간 운임 − 뺀 배송비. 0원은 보내지 않으므로 항상 1 이상이다
 */
public record WalletRefundRequest(
        Long refundId,
        Long orderId,
        Long buyerId,
        Long sellerId,
        long amount
) {
}
