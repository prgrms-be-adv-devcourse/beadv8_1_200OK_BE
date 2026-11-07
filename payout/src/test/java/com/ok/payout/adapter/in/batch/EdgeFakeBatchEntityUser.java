package com.ok.payout.adapter.in.batch;

import com.ok.payout.domain.PayoutSnapshot;

// ArchitectureRuleEdgeTest가 규칙이 실제로 위반을 잡는지 보려고 일부러 만든 클래스다. 다른 곳에서 쓰지 않는다. 애노테이션을 붙이지 않아 Spring 빈이 되지 않는다.
// 배치가 domain 엔티티(PayoutSnapshot)를 직접 쓰는 위반 예. 배치 규칙이 막아야 한다
public class EdgeFakeBatchEntityUser {

    private PayoutSnapshot snapshot;

    public PayoutSnapshot snapshot() {
        return snapshot;
    }
}
