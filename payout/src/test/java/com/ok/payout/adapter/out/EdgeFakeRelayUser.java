package com.ok.payout.adapter.out;

import com.ok.relay.EdgeFakeRelayPublisher;

// ArchitectureRuleEdgeTest가 규칙이 실제로 위반을 잡는지 보려고 일부러 만든 위반 클래스다. 다른 곳에서 쓰지 않는다. 애노테이션을 붙이지 않아 Spring 빈이 되지 않는다.
// 정산 클래스가 com.ok의 정산·common 밖 패키지(com.ok.relay)를 쓰는 예
public class EdgeFakeRelayUser {

    private final EdgeFakeRelayPublisher publisher = new EdgeFakeRelayPublisher();

    public EdgeFakeRelayPublisher publisher() {
        return publisher;
    }
}
