package com.ok.payout.adapter.out.event.dto;

import java.util.ArrayList;
import java.util.List;

import com.ok.payout.application.port.out.PayoutDepositCheckRequested;

/**
 * 정산이 지갑으로 보내는 대사 질문 묶음 메시지 (#619). Kafka에 JSON으로 실려 나간다.
 * 필드 이름·타입·단위가 지갑과의 약속(PR #548 문서 4-2절, 지갑 #613)이라, 바꾸면 지갑 담당과 먼저 맞춘다.
 */
public record PayoutDepositCheckRequestedMessage(
        // 질문 묶음 번호. 지갑이 답에 그대로 돌려줌
        Long checkId,
        // 최대 500건
        List<Item> items
) {

    public record Item(
            // 확인할 정산 ID. 지갑은 원장 키 PAYOUT:{payoutId}로 찾음
            Long payoutId,
            // 정산이 기대하는 판매자 회원 ID
            Long payeeId,
            // 정산이 기대하는 금액(원), 1 이상
            Long amount
    ) {
    }

    public static PayoutDepositCheckRequestedMessage from(PayoutDepositCheckRequested requested) {
        List<Item> items = new ArrayList<>();
        for (PayoutDepositCheckRequested.Item item : requested.items()) {
            items.add(new Item(item.payoutId(), item.payeeId(), item.amount()));
        }
        return new PayoutDepositCheckRequestedMessage(requested.checkId(), items);
    }
}
