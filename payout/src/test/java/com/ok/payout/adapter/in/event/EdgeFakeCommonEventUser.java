package com.ok.payout.adapter.in.event;

import com.ok.common.event.OrderProductConfirmedEvent;

// ArchitectureRuleEdgeTest가 규칙이 실제로 위반을 잡는지 보려고 일부러 만든 위반 클래스다. 다른 곳에서 쓰지 않는다. 애노테이션을 붙이지 않아 Spring 빈이 되지 않는다.
// 예전 규칙이 공통 이벤트 클래스를 허용하던 자리(adapter/in/event)에서 common.event를 쓰는 예. Spring 입구를 지운 뒤(#781)에는 이 자리도 막혀야 한다
public class EdgeFakeCommonEventUser {

    private OrderProductConfirmedEvent event;

    public OrderProductConfirmedEvent event() {
        return event;
    }
}
