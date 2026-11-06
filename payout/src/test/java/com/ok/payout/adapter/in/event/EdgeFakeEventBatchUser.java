package com.ok.payout.adapter.in.event;

import com.ok.payout.adapter.in.batch.PayoutJobConfig;

// ArchitectureRuleEdgeTest가 규칙이 실제로 위반을 잡는지 보려고 일부러 만든 위반 클래스다. 다른 곳에서 쓰지 않는다. 애노테이션을 붙이지 않아 Spring 빈이 되지 않는다.
// 웹이 아닌 다른 입구(event)가 배치 내부(PayoutJobConfig)를 쓰는 예. web → event → batch 우회 경로가 된다
public class EdgeFakeEventBatchUser {

    private PayoutJobConfig config;

    public PayoutJobConfig config() {
        return config;
    }
}
