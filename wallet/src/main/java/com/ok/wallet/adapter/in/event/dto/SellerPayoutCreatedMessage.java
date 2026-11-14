package com.ok.wallet.adapter.in.event.dto;

import java.time.LocalDateTime;

/**
 * 정산이 Kafka로 보내는 정산금 확정 메시지를 지갑이 읽는 모양. 필드는 정산과 합의한 JSON 약속이다(#339).
 * 정산 클래스를 import하지 않고 같은 JSON 모양의 지갑 DTO로 읽는다. 빠진 필드는 null로 읽히고 값 검사는 리스너가 한다.
 *
 * @param payoutId  정산 ID. 지갑이 중복 입금을 막는 기준
 * @param payeeId   정산금을 받는 판매자 회원 ID
 * @param amount    정산 금액(원). 정산은 1 이상만 보낸다
 * @param createdAt 정산 확정 시각(Asia/Seoul)
 */
public record SellerPayoutCreatedMessage(
        Long payoutId,
        Long payeeId,
        Long amount,
        LocalDateTime createdAt
) {
}
