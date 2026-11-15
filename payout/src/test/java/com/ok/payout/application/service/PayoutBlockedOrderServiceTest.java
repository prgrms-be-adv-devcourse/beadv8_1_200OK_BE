package com.ok.payout.application.service;

import com.ok.payout.application.port.out.PayoutBlockedOrderRepository;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

// 서비스 단위 테스트: 막힌 주문 표 갱신의 순서 (묶음 나누기, 반영 표시를 언제 올리는지) (#582)
// 실제 판정·저장은 PayoutBlockedOrderIntegrationTest
@ExtendWith(MockitoExtension.class)
class PayoutBlockedOrderServiceTest {

    @Mock
    private PayoutQueryRepository payoutQueryRepository;

    @Mock
    private PayoutBlockedOrderRepository payoutBlockedOrderRepository;

    @Mock
    private PayoutBlockedOrderRefreshService payoutBlockedOrderRefreshService;

    @InjectMocks
    private PayoutBlockedOrderService sut;

    @Test
    void 대상이_묶음_크기보다_하나_많으면_두_번에_나눠_판정하고_반영_표시는_마지막에_한_번_올린다() {
        // given: 반영 표시 0, 최대 실패 기록 ID 5000, 대상 주문 1,001개 (표에 있는 주문 1 + 새 실패의 주문 1,000)
        int total = PayoutBlockedOrderService.REFRESH_CHUNK_SIZE + 1;
        List<Long> newFailureOrderIds = new ArrayList<>();
        for (long orderId = 2; orderId <= total; orderId++) {
            newFailureOrderIds.add(orderId);
        }
        given(payoutBlockedOrderRefreshService.lastReflectedFailureId()).willReturn(0L);
        given(payoutQueryRepository.findMaxFailureId()).willReturn(5_000L);
        given(payoutBlockedOrderRepository.findAllOrderIds()).willReturn(List.of(1L));
        given(payoutQueryRepository.findOrderIdsWithFailuresBetween(0L, 5_000L)).willReturn(newFailureOrderIds);

        // when
        sut.refresh();

        // then: 1,000개 + 1개, 그다음에 반영 표시
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> chunks = ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(payoutBlockedOrderRefreshService);
        order.verify(payoutBlockedOrderRefreshService, times(2)).refreshOrders(chunks.capture());
        order.verify(payoutBlockedOrderRefreshService).markReflected(5_000L);
        assertThat(chunks.getAllValues()).extracting(List::size)
                .containsExactly(PayoutBlockedOrderService.REFRESH_CHUNK_SIZE, 1);
        assertThat(chunks.getAllValues().get(0)).startsWith(1L, 2L);
        assertThat(chunks.getAllValues().get(1)).containsExactly((long) total);
    }

    @Test
    void 두번째_묶음이_실패하면_반영_표시를_올리지_않고_예외를_그대로_던진다() {
        // given: 대상 1,001개, 두 번째 묶음 저장에서 DB 오류
        int total = PayoutBlockedOrderService.REFRESH_CHUNK_SIZE + 1;
        List<Long> orderIds = new ArrayList<>();
        for (long orderId = 1; orderId <= total; orderId++) {
            orderIds.add(orderId);
        }
        given(payoutBlockedOrderRefreshService.lastReflectedFailureId()).willReturn(0L);
        given(payoutQueryRepository.findMaxFailureId()).willReturn(5_000L);
        given(payoutBlockedOrderRepository.findAllOrderIds()).willReturn(List.of());
        given(payoutQueryRepository.findOrderIdsWithFailuresBetween(0L, 5_000L)).willReturn(orderIds);
        RuntimeException dbError = new IllegalStateException("저장 실패");
        // 첫 묶음(1,000개)은 통과, 두 번째 묶음(1개)에서만 실패
        willAnswer(invocation -> {
            List<Long> chunk = invocation.getArgument(0);
            if (chunk.size() == 1) {
                throw dbError;
            }
            return null;
        }).given(payoutBlockedOrderRefreshService).refreshOrders(anyList());

        // when & then: 앞 묶음은 이미 커밋됐지만 반영 표시는 그대로 → 다음 갱신이 같은 범위를 처음부터 다시 판정 (다시 판정은 결과가 같음)
        assertThatThrownBy(() -> sut.refresh()).isSameAs(dbError);
        then(payoutBlockedOrderRefreshService).should(never()).markReflected(anyLong());
    }

    @Test
    void 가장_큰_실패_기록_ID가_반영_표시보다_작으면_반영_표시_그대로_범위를_잡는다() {
        // given: 반영 표시 900인데 최대 ID 800 (최근 실패 기록을 사람이 지운 경우)
        given(payoutBlockedOrderRefreshService.lastReflectedFailureId()).willReturn(900L);
        given(payoutQueryRepository.findMaxFailureId()).willReturn(800L);
        given(payoutBlockedOrderRepository.findAllOrderIds()).willReturn(List.of());
        given(payoutQueryRepository.findOrderIdsWithFailuresBetween(900L, 900L)).willReturn(List.of());

        // when
        sut.refresh();

        // then: 반영 표시를 뒤로 돌리지 않음, 다시 판정할 주문이 없으면 판정을 부르지 않음
        then(payoutBlockedOrderRefreshService).should().markReflected(900L);
        then(payoutBlockedOrderRefreshService).should(never()).refreshOrders(anyList());
    }
}
