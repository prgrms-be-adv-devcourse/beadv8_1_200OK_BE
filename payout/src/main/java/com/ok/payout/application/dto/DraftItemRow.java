package com.ok.payout.application.dto;

import com.ok.payout.domain.PayoutEventType;

// draft 항목 한 줄 (판매자 몫·시스템 몫 공통). verifyStep 결과 검증(#290)에서 스냅샷·실패 기록과 후보 단위로 비교하는 데도 씀
//   headerSellerId: 판매자 몫이면 그 항목이 묶인 판매자 draft(헤더)의 판매자, 시스템 몫이면 null
//                   commitStep은 헤더의 판매자로 정산 수취인을 정하므로, 항목의 판매자와 다르면 돈이 다른 판매자에게 감
//   systemShare: 시스템 몫 테이블에서 왔으면 true
public record DraftItemRow(Long candidateId, Long orderId, Long sellerId, Long headerSellerId,
                           PayoutEventType eventType, Long amount, boolean systemShare) {
}
