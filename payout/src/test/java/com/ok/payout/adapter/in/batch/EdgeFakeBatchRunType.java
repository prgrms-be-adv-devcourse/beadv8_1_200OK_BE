package com.ok.payout.adapter.in.batch;

import com.ok.payout.domain.type.PayoutRunType;

// ArchitectureRuleEdgeTest가 규칙이 실제로 위반을 잡는지 보려고 일부러 만든 클래스다. 다른 곳에서 쓰지 않는다. 애노테이션을 붙이지 않아 Spring 빈이 되지 않는다.
// 배치가 domain.type의 enum(PayoutRunType)을 쓰는 정상 예. 배치 규칙이 허용해야 한다
public class EdgeFakeBatchRunType {

    private PayoutRunType runType;

    public PayoutRunType runType() {
        return runType;
    }
}
