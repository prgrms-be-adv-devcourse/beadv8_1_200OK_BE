package com.ok.payout.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.payout.application.port.in.PayoutOrderResolutionResult;
import com.ok.payout.application.port.in.PayoutOrderResolveCommand;
import com.ok.payout.application.port.out.PayoutFailureResolutionRepository;
import com.ok.payout.application.port.out.PayoutQueryRepository;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutFailureResolution;
import com.ok.payout.exception.PayoutErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// 서비스 단위 테스트: 해결할 것이 있는지 판정, 저장 대상, 유니크 위반 처리 (#568). 정상 흐름은 PayoutFailureResolutionIntegrationTest
@ExtendWith(MockitoExtension.class)
class PayoutFailureResolutionServiceTest {

    private static final long ORDER_ID = 100L;
    private static final long ADMIN_ID = 1L;
    private static final PayoutOrderResolveCommand COMMAND = new PayoutOrderResolveCommand(ORDER_ID, ADMIN_ID, "메모");

    @Mock
    private PayoutQueryRepository payoutQueryRepository;

    @Mock
    private PayoutFailureResolutionRepository payoutFailureResolutionRepository;

    @InjectMocks
    private PayoutFailureResolutionService sut;

    @Test
    void 잠기지도_멈추지도_않은_주문은_409이고_저장하지_않는다() {
        // given: 잠금 없음, 성공한 실행에서 2번 실패(멈춤 기준 3번 미만)
        given(payoutQueryRepository.isOrderLocked(ORDER_ID)).willReturn(false);
        given(payoutQueryRepository.countFailedRunsByOrder()).willReturn(Map.of(ORDER_ID, 2L));

        // when & then
        assertThatThrownBy(() -> sut.resolveOrder(COMMAND))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.NOTHING_TO_RESOLVE));
        then(payoutFailureResolutionRepository).should(never()).saveAll(any());
    }

    @Test
    void 멈춘_주문은_해결되지_않은_실패_기록_전부를_조회한_순서_그대로_저장한다() {
        // given: 3번 실패해 멈춤, 해결 안 된 실패 기록 3건(잠그는 사유와 아닌 사유 섞임)
        List<PayoutFailure> failures = List.of(
                failure(11L, PayoutFailureReason.AMOUNT_MISMATCH),
                failure(12L, PayoutFailureReason.AMOUNT_MISMATCH),
                failure(13L, PayoutFailureReason.VERIFY_MISMATCH));
        given(payoutQueryRepository.isOrderLocked(ORDER_ID)).willReturn(false);
        given(payoutQueryRepository.countFailedRunsByOrder()).willReturn(Map.of(ORDER_ID, 3L));
        given(payoutQueryRepository.findUnresolvedFailuresByOrderId(ORDER_ID)).willReturn(failures);
        given(payoutFailureResolutionRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

        // when
        PayoutOrderResolutionResult result = sut.resolveOrder(COMMAND);

        // then: 사유를 가리지 않고 3건 모두, 조회 결과 순서 그대로 저장 (id 오름차순 정렬은 조회가 맡고 통합 테스트가 확인)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PayoutFailureResolution>> captor = ArgumentCaptor.forClass(List.class);
        then(payoutFailureResolutionRepository).should().saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(PayoutFailureResolution::getFailureId).containsExactly(11L, 12L, 13L);
        assertThat(result.resolvedFailureCount()).isEqualTo(3);
    }

    @Test
    void 잠긴_주문은_실패_횟수가_적어도_해결한다() {
        // given: 실패한 실행에서 잠겨 횟수는 0
        given(payoutQueryRepository.isOrderLocked(ORDER_ID)).willReturn(true);
        given(payoutQueryRepository.countFailedRunsByOrder()).willReturn(Map.of());
        given(payoutQueryRepository.findUnresolvedFailuresByOrderId(ORDER_ID))
                .willReturn(List.of(failure(21L, PayoutFailureReason.VERIFY_MISMATCH)));
        given(payoutFailureResolutionRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

        // when
        PayoutOrderResolutionResult result = sut.resolveOrder(COMMAND);

        // then
        assertThat(result.resolvedFailureCount()).isEqualTo(1);
    }

    @Test
    void 해결_기록_유니크_위반은_409로_바꾼다() {
        // given: 같은 주문을 동시에 해결해 두 번째 저장이 유니크에 걸린 상황
        givenLockedOrderWithOneFailure();
        given(payoutFailureResolutionRepository.saveAll(any())).willThrow(new DataIntegrityViolationException(
                "Duplicate entry '21' for key 'payout_failure_resolution.uk_payout_failure_resolution_failure_id'"));

        // when & then
        assertThatThrownBy(() -> sut.resolveOrder(COMMAND))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.NOTHING_TO_RESOLVE));
    }

    @Test
    void 유니크_위반이_아닌_DB_오류는_409로_가리지_않고_그대로_던진다() {
        // given: 코드 버그로 다른 제약에 걸린 상황 (예: 없는 실패 기록 ID)
        givenLockedOrderWithOneFailure();
        DataIntegrityViolationException other = new DataIntegrityViolationException(
                "Cannot add or update a child row: a foreign key constraint fails (fk_payout_failure_resolution_failure_id)");
        given(payoutFailureResolutionRepository.saveAll(any())).willThrow(other);

        // when & then
        assertThatThrownBy(() -> sut.resolveOrder(COMMAND)).isSameAs(other);
    }

    private void givenLockedOrderWithOneFailure() {
        given(payoutQueryRepository.isOrderLocked(ORDER_ID)).willReturn(true);
        given(payoutQueryRepository.countFailedRunsByOrder()).willReturn(Map.of());
        given(payoutQueryRepository.findUnresolvedFailuresByOrderId(ORDER_ID))
                .willReturn(List.of(failure(21L, PayoutFailureReason.VERIFY_MISMATCH)));
    }

    // 저장된 실패 기록처럼 id를 채움 (엔티티에 id를 넣는 생성 경로가 없어 테스트에서만 넣음)
    private static PayoutFailure failure(Long id, PayoutFailureReason reason) {
        PayoutCandidateItem candidate = PayoutCandidateItem.ofSaleAmount(ORDER_ID, 1001L, 10L, 1_000L);
        ReflectionTestUtils.setField(candidate, "id", id + 1000);
        PayoutFailure failure = PayoutFailure.of(1L, candidate, reason);
        ReflectionTestUtils.setField(failure, "id", id);
        return failure;
    }
}
