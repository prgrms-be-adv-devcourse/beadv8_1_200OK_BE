package com.ok.wallet.adapter.in.event.dto;

import java.util.List;

/**
 * 정산이 Kafka로 묻는 정산금 입금 확인 묶음을 지갑이 읽는 모양. 필드 이름·타입은 정산과의 약속이다(정산 대사 #308).
 * 정산 클래스를 import하지 않고 같은 JSON 모양의 지갑 DTO로 읽는다. 값이 비었는지는 리스너가 검사한다.
 *
 * @param checkId 묶음 번호. 답에 그대로 돌려준다
 * @param items   한 묶음 최대 500건(정산 약속)
 */
public record PayoutDepositCheckRequestedMessage(
        Long checkId,
        List<Item> items
) {

    /**
     * @param payeeId 정산이 기대하는 판매자 회원 ID. 판정은 정산이 하므로 지갑은 읽을 수 없는 항목을 뺄 때 로그에만 남긴다
     * @param amount  정산이 기대하는 금액(원). payeeId와 같은 이유로 로그에만 쓴다
     */
    public record Item(
            Long payoutId,
            Long payeeId,
            Long amount
    ) {
    }
}
