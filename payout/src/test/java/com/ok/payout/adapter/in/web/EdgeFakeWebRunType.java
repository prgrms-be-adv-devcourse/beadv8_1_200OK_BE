package com.ok.payout.adapter.in.web;

import com.ok.payout.domain.type.PayoutRunType;

// ArchitectureRuleEdgeTest가 규칙이 실제로 위반을 잡는지 보려고 일부러 만든 위반 클래스다. 다른 곳에서 쓰지 않는다. 애노테이션을 붙이지 않아 Spring 빈이 되지 않는다.
// 웹이 domain.type의 enum(PayoutRunType)을 쓰는 예. 배치만 domain.type을 쓸 수 있다
public class EdgeFakeWebRunType {

    private PayoutRunType runType;

    public PayoutRunType runType() {
        return runType;
    }
}
