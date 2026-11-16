package com.ok.wallet.adapter.out.event.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code wallet.payout-deposit-checked.v1} 토픽(옮기는 동안 옛 이름에도)에 보내는 메시지. 필드 이름·타입이 정산과의 약속이다(정산 대사 #308).
 * 정산은 이 클래스가 아니라 JSON 모양으로 읽는다(타입 헤더를 싣지 않음). 필드를 바꾸면 정산 쪽도 같이 바꿔야 한다.
 *
 * @param checkId 정산이 보낸 묶음 번호 그대로
 */
public record PayoutDepositCheckedMessage(
        Long checkId,
        List<Item> items
) {

    /**
     * @param found       원장에 {@code PAYOUT:{payoutId}}가 있으면 true. false면 나머지 값은 null이다
     * @param payeeId     원장이 속한 판매자 지갑의 회원 ID
     * @param amount      원장 금액(원)
     * @param depositedAt 지갑이 입금한 시각. 오프셋 없는 ISO-8601(Asia/Seoul)로 나간다
     */
    public record Item(
            Long payoutId,
            boolean found,
            Long payeeId,
            Long amount,
            LocalDateTime depositedAt
    ) {
    }
}
