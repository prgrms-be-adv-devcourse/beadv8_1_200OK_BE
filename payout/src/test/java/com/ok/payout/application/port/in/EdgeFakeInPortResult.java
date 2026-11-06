package com.ok.payout.application.port.in;

import com.ok.payout.application.port.out.PayoutItemRow;

// ArchitectureRuleEdgeTest가 규칙이 실제로 위반을 잡는지 보려고 일부러 만든 위반 클래스다. 다른 곳에서 쓰지 않는다. 애노테이션을 붙이지 않아 Spring 빈이 되지 않는다.
// 입력 포트의 값이 출력 포트의 Row를 쓰는 예
public record EdgeFakeInPortResult(PayoutItemRow row) {
}
