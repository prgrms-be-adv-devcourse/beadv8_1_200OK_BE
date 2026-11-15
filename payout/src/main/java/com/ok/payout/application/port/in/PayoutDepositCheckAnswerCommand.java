package com.ok.payout.application.port.in;

import java.time.LocalDateTime;
import java.util.List;

// 지갑이 보낸 대사 답 묶음 하나 (#619). 리스너가 메시지 DTO를 이 값으로 바꿔 유스케이스를 부름
public record PayoutDepositCheckAnswerCommand(
        // 질문 묶음 번호 (정산이 보낸 값 그대로)
        Long checkId,
        List<Item> items
) {

    public PayoutDepositCheckAnswerCommand {
        items = List.copyOf(items);
    }

    public record Item(
            Long payoutId,
            // 지갑 원장에 PAYOUT:{payoutId}가 있으면 true
            boolean found,
            // 원장이 속한 판매자 지갑의 회원 ID. 없으면 null
            Long payeeId,
            // 원장 금액(원). 없으면 null
            Long amount,
            // 지갑이 입금한 시각. 없으면 null
            LocalDateTime depositedAt
    ) {
    }
}
