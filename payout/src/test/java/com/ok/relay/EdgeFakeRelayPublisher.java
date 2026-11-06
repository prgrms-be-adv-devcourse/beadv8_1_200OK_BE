package com.ok.relay;

import com.ok.payout.application.port.out.PayoutEventPublisher;
import com.ok.payout.application.port.out.SellerPayoutCreated;

// ArchitectureRuleEdgeTest가 규칙이 실제로 위반을 잡는지 보려고 일부러 만든 위반 클래스다. 다른 곳에서 쓰지 않는다. 애노테이션을 붙이지 않아 Spring 빈이 되지 않는다.
// 정산 밖 패키지(com.ok.relay)에서 정산 출력 포트를 구현하는 클래스
public class EdgeFakeRelayPublisher implements PayoutEventPublisher {

    @Override
    public void publishSellerPayoutCreated(SellerPayoutCreated created) {
    }
}
