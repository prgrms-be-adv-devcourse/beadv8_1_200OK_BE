package com.ok.wallet.adapter.in.event.dto;

/**
 * 환불이 Kafka로 보내는 되돌리기(돌려준 예치금 다시 가져오기) 요청을 지갑이 읽는 모양. 필드 이름·타입은 환불과의 약속이다(#611, PR #537).
 * 값이 비었는지는 DTO가 아니라 리스너가 검사한다. 빠진 필드를 0으로 읽어 버리지 않도록 amount는 Long으로 받는다.
 *
 * @param buyerId 환불이 아는 구매자. 지갑은 반환 처리 기록의 구매자에게서 가져오고, 반환 기록이 없을 때만 이 값을 기록에 쓴다
 * @param amount  환불이 아는 반환 금액(원). 쓰는 곳은 buyerId와 같다
 */
public record RefundWalletRevertRequestedMessage(
        Long refundId,
        Long orderId,
        Long buyerId,
        Long amount
) {
}
