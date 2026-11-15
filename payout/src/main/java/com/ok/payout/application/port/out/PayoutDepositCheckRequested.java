package com.ok.payout.application.port.out;

import java.util.List;

// 대사 질문 묶음 하나를 발행 인터페이스(PayoutEventPublisher)에 넘기는 값 (#619)
// SellerPayoutCreated와 같은 이유로 port/out에 둠. 필드는 지갑과 약속한 메시지(PayoutDepositCheckRequestedMessage)와 같다
public record PayoutDepositCheckRequested(
        // 질문 묶음 번호. 지갑이 답에 그대로 돌려줌
        Long checkId,
        List<Item> items
) {

    public PayoutDepositCheckRequested {
        items = List.copyOf(items);
    }

    public record Item(
            // 확인할 정산 ID. 지갑은 원장 키 PAYOUT:{payoutId}로 찾음
            Long payoutId,
            // 정산이 기대하는 판매자 회원 ID (지갑은 답에 쓰지 않고 로그에만 남김)
            Long payeeId,
            // 정산이 기대하는 금액(원), 1 이상
            Long amount
    ) {
    }
}
