package com.ok.payout.service;

import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.repository.PayoutQueryRepository.FailureRow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 결과 검증(verify)에 걸려 이번 정산에서 뺄 draft 항목의 기준 (#290)
// verify(남은 항목 수 판단)와 commitStep(실제로 빼기)이 같은 규칙을 쓰도록 한 곳에 둠 (둘이 어긋나면 "0건 FAILED" 판단과 실제 정산이 달라짐)
//
// 빼는 항목 = VERIFY_MISMATCH 기록의 주문에 속한 항목 + VERIFY_MISMATCH 기록이 있는 후보의 항목
//   주문 기준: 같은 주문의 정상 후보(배송비 등)도 함께 뺌
//   후보 기준도 두는 이유: draft 항목의 주문 ID가 후보와 다르게 들어간 버그라면, 실패 기록(후보에서 주문 ID를 복사)의 주문으로는 그 항목을 못 거름
// draft는 INSERT 전용 기록이라 지우지 않고, 이 기준으로 commitStep 조회 결과에서 거름
final class PayoutVerifyExclusion {

    private final Set<Long> orderIds = new HashSet<>();
    private final Set<Long> candidateIds = new HashSet<>();

    private PayoutVerifyExclusion() {
    }

    // 이번 스냅샷 실패 기록 중 VERIFY_MISMATCH만 기준으로 씀 (validate·draft 실패 후보는 애초에 draft에 들어가지 않음)
    static PayoutVerifyExclusion from(List<FailureRow> failures) {
        PayoutVerifyExclusion exclusion = new PayoutVerifyExclusion();
        for (FailureRow failure : failures) {
            if (failure.reason() == PayoutFailureReason.VERIFY_MISMATCH) {
                exclusion.orderIds.add(failure.orderId());
                exclusion.candidateIds.add(failure.candidateId());
            }
        }
        return exclusion;
    }

    // verify가 이번에 만든 기록을 더함 (저장하기 전에 남은 항목 수를 계산하므로 아직 DB에 없음)
    void addAll(List<PayoutFailure> failures) {
        for (PayoutFailure failure : failures) {
            orderIds.add(failure.getOrderId());
            candidateIds.add(failure.getCandidateId());
        }
    }

    boolean excludes(Long orderId, Long candidateId) {
        return orderIds.contains(orderId) || candidateIds.contains(candidateId);
    }

    int orderCount() {
        return orderIds.size();
    }
}
