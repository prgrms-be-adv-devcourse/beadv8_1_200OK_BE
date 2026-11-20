package com.ok.refund.application.port.out;

/**
 * 지갑에 보내는 돈 다시 가져오기(회수) 요청 값(#546). 메시지 모양(토픽·JSON 필드 이름)은 어댑터가 정하고, 서비스는 이 값만 넘긴다.
 *
 * @param refundId 지갑이 같은 요청을 두 번 받아도 한 번만 가져오도록 중복을 가르는 값(PR #537 약속). 반환 요청과 같은 refundId다
 * @param buyerId  돈을 다시 가져올 구매자
 * @param amount   다시 가져올 금액 (원). 반환 요청에 보낸 금액과 같다
 */
public record WalletRevertRequest(
        Long refundId,
        Long orderId,
        Long buyerId,
        long amount
) {
}
