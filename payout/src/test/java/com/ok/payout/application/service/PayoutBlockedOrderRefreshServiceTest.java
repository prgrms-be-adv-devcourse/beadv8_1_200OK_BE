package com.ok.payout.application.service;

import com.ok.payout.application.port.out.BlockedFailureRow;
import com.ok.payout.application.port.out.PayoutBlockedOrderCauseRepository;
import com.ok.payout.application.port.out.PayoutBlockedOrderRefreshRepository;
import com.ok.payout.application.port.out.PayoutBlockedOrderRepository;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import com.ok.payout.domain.PayoutBlockedOrder;
import com.ok.payout.domain.PayoutBlockedOrderCause;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailureReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

// 서비스 단위 테스트: 막힌 주문 묶음 판정 중 한 주문의 판정이 실패할 때, 판정 규칙의 예외 경로 (#582)
// 정상 흐름(넣기·고치기·지우기)은 PayoutBlockedOrderIntegrationTest
@ExtendWith(MockitoExtension.class)
class PayoutBlockedOrderRefreshServiceTest {

    private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 11, 14, 10, 0);

    @Mock
    private PayoutQueryRepository payoutQueryRepository;

    @Mock
    private PayoutBlockedOrderRepository payoutBlockedOrderRepository;

    @Mock
    private PayoutBlockedOrderCauseRepository payoutBlockedOrderCauseRepository;

    @Mock
    private PayoutBlockedOrderRefreshRepository payoutBlockedOrderRefreshRepository;

    @InjectMocks
    private PayoutBlockedOrderRefreshService sut;

    @Test
    void 판정에_실패한_주문_하나만_건너뛰고_나머지는_저장한다() {
        // given: 주문 1·2 모두 잠김으로 보이지만, 주문 1은 해결 안 된 실패 기록이 없음 (조회 조건이 어긋난 코드 버그를 흉내)
        List<Long> orderIds = List.of(1L, 2L);
        given(payoutQueryRepository.findLockedOrderIds(orderIds)).willReturn(Set.of(1L, 2L));
        given(payoutQueryRepository.countFailedRunsOfOrders(orderIds)).willReturn(Map.of(2L, 1L));
        given(payoutQueryRepository.findUnresolvedFailureRowsOfOrders(orderIds))
                .willReturn(List.of(failure(2L, PayoutFailureReason.VERIFY_MISMATCH, "[원인] 금액 다름")));
        given(payoutBlockedOrderRepository.findAllByOrderIdIn(List.of(2L))).willReturn(List.of());

        // when: 예외가 밖으로 나가지 않음
        sut.refreshOrders(orderIds);

        // then: 주문 2만 원인을 지우고 다시 넣음. 주문 1의 줄·원인은 건드리지 않음
        then(payoutBlockedOrderCauseRepository).should().deleteAllByOrderIdIn(List.of(2L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PayoutBlockedOrder>> blocked = ArgumentCaptor.forClass(List.class);
        then(payoutBlockedOrderRepository).should().saveAll(blocked.capture());
        assertThat(blocked.getValue()).extracting(PayoutBlockedOrder::getOrderId).containsExactly(2L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PayoutBlockedOrderCause>> causes = ArgumentCaptor.forClass(List.class);
        then(payoutBlockedOrderCauseRepository).should().saveAll(causes.capture());
        assertThat(causes.getValue()).extracting(PayoutBlockedOrderCause::getOrderId).containsExactly(2L);
    }

    @Test
    void 막혔는데_해결_안_된_실패_기록이_없으면_판정이_실패한다() {
        // when & then: 조회 조건이 서로 어긋난 것(코드 버그)이라 조용히 넘기지 않음 (부르는 쪽이 이 주문만 건너뛰고 ERROR를 남김)
        assertThatThrownBy(() -> PayoutBlockedOrderRefreshService.judge(1L, true, 0L, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("막힌 주문인데 해결 안 된 실패 기록이 없습니다. orderId=1");
    }

    @Test
    void 함께_보류_기록만_남았으면_그_기록을_원인으로_보여_준다() {
        // given: 결과 검증의 원인 기록은 이미 빠지고 "[함께 보류]" 기록만 남은 잠긴 주문
        List<BlockedFailureRow> failures = List.of(
                failure(1L, PayoutFailureReason.VERIFY_MISMATCH, "[함께 보류] 같은 주문의 결과 검증 불일치 (원인 후보 [9])"));

        // when
        PayoutBlockedOrderRefreshService.JudgedOrder sut = PayoutBlockedOrderRefreshService.judge(1L, true, 1L, failures);

        // then: 원인 칸이 비면 왜 막혔는지 알 수 없어 남은 기록이라도 둠
        assertThat(sut.causes()).extracting(PayoutBlockedOrderCause::getDetail)
                .containsExactly("[함께 보류] 같은 주문의 결과 검증 불일치 (원인 후보 [9])");
    }

    private static BlockedFailureRow failure(Long orderId, PayoutFailureReason reason, String detail) {
        return new BlockedFailureRow(orderId, 100L, 10L, PayoutEventType.SALE_AMOUNT, reason, detail, FAILED_AT);
    }
}
