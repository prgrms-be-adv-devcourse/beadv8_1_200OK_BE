package com.ok.payout.adapter.in.event.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 지갑이 Kafka로 보내는 대사 답 묶음을 정산이 읽는 모양 (#619). 필드는 PR #548 문서 4-3절과 지갑 #613으로 합의했다.
 * 지갑 클래스를 import하지 않고 같은 JSON 모양의 정산 DTO로 읽는다 (컨벤션 2.1).
 */
public record PayoutDepositCheckedMessage(
        // 정산이 보낸 질문 묶음 번호 그대로
        Long checkId,
        List<Item> items
) {

    public record Item(
            // 정산이 물은 정산 ID 그대로
            Long payoutId,
            // 지갑 원장에 PAYOUT:{payoutId}가 있으면 true
            Boolean found,
            // 그 원장이 속한 판매자 지갑의 회원 ID. 없으면 null
            Long payeeId,
            // 원장 금액(원). 없으면 null
            Long amount,
            // 원장 created_at(지갑이 입금한 시각), 오프셋 없는 ISO-8601 (Asia/Seoul). 없으면 null
            LocalDateTime depositedAt
    ) {
    }
}
