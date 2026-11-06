package com.ok.payout.adapter.in.shared;

import com.ok.payout.adapter.in.batch.PayoutJobScheduler;

// ArchitectureRuleEdgeTest가 규칙이 실제로 위반을 잡는지 보려고 일부러 만든 위반 클래스다. 다른 곳에서 쓰지 않는다. 애노테이션을 붙이지 않아 Spring 빈이 되지 않는다.
// adapter.in 아래 새 하위 패키지(shared)가 웹과 배치 사이 우회 경로가 되는 예
public class EdgeFakeBridge {

    private PayoutJobScheduler scheduler;

    public PayoutJobScheduler scheduler() {
        return scheduler;
    }
}
