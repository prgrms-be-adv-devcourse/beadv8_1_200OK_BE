package com.ok.payout.adapter.in.web;

import com.ok.payout.adapter.in.batch.PayoutJobScheduler;

// ArchitectureRuleEdgeTest가 규칙이 실제로 위반을 잡는지 보려고 일부러 만든 위반 클래스다. 다른 곳에서 쓰지 않는다. 애노테이션을 붙이지 않아 Spring 빈이 되지 않는다.
// 웹이 배치 중 PayoutJobRunner가 아닌 클래스(PayoutJobScheduler)를 쓰는 예
public class EdgeFakeWebScheduler {

    private PayoutJobScheduler scheduler;

    public PayoutJobScheduler scheduler() {
        return scheduler;
    }
}
