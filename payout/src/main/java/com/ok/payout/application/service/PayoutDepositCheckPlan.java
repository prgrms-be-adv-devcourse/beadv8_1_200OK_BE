package com.ok.payout.application.service;

import com.ok.payout.application.port.out.PayoutDepositCheckRequested;

import java.util.List;

// 지갑에 보낼 질문 묶음 하나와, 보내기에 실패했을 때 되돌릴 대사 줄 (#619)
// 트랜잭션 안에서 묶음 번호를 받고 줄에 "물음"을 적은 뒤, 트랜잭션 밖에서 Kafka로 보내려고 서비스 사이에 넘기는 값
record PayoutDepositCheckPlan(
        PayoutDepositCheckRequested requested,
        // 이 묶음에 담은 대사 줄 ID
        List<Long> checkRowIds,
        // 다음 묶음은 이 ID 뒤부터 읽음
        Long lastCheckRowId
) {

    PayoutDepositCheckPlan {
        checkRowIds = List.copyOf(checkRowIds);
    }
}
