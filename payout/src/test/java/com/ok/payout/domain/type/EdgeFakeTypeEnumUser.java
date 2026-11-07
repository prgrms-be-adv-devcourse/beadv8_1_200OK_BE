package com.ok.payout.domain.type;

import com.ok.payout.domain.PayoutPolicy;

// ArchitectureRuleEdgeTest가 규칙이 실제로 위반을 잡는지 보려고 일부러 만든 위반 클래스다. 다른 곳에서 쓰지 않는다. 애노테이션을 붙이지 않아 Spring 빈이 되지 않는다.
// domain.type의 enum이 정산의 다른 패키지(domain의 PayoutPolicy)를 쓰는 예
public enum EdgeFakeTypeEnumUser {

    ONE;

    public Class<?> policy() {
        return PayoutPolicy.class;
    }
}
