package com.ok.payout.service;

import com.ok.common.event.SellerPayoutCreatedEvent;
import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutErrorCode;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.repository.PayoutQueryRepository;
import com.ok.payout.repository.PayoutRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


// 단위 테스트: Spring 없이, 리포지토리는 Mockito 가짜 객체로 대체
// DB가 필요한 검증(기간 조건, NOT EXISTS, 반복 실행)은 PayoutSettleIntegrationTest에서 확인.
@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

    @Mock
    private PayoutQueryRepository payoutQueryRepository;

    @Mock
    private PayoutRepository payoutRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PayoutService payoutService;

    // saveAll에 넘어간 Payout 목록을 붙잡아서 검증할 때 사용
    @Captor
    private ArgumentCaptor<List<Payout>> payoutsCaptor;

    // publishEvent에 넘어간 이벤트를 붙잡아서 검증할 때 사용
    @Captor
    private ArgumentCaptor<SellerPayoutCreatedEvent> eventCaptor;


    @Test
    @DisplayName("판매자 Payout 2건 + 시스템 Payout 1건이 생기고, 금액이 맞음")
    void createsSellerAndSystemPayouts_whenCandidatesExist() {
        // Arrange
        LocalDateTime from = LocalDateTime.of(2026, 9, 21, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 25, 0, 0);
        List<Long> sellerIds = List.of(10L, 20L);
        List<PayoutCandidateItem> candidates = List.of(
                // 판매자 10: 판매가 10,000 / 배송비 3,000
                withId(PayoutCandidateItem.ofSaleAmount(1001L, 10L, 9_700L), 1L),
                withId(PayoutCandidateItem.ofSaleFee(1001L, 10L, 300L), 2L),
                withId(PayoutCandidateItem.ofSaleShippingFee(1001L, 10L, 3_000L), 3L),
                // 판매자 20: 판매가 20,000 / 배송비 없음
                withId(PayoutCandidateItem.ofSaleAmount(2001L, 20L, 19_400L), 4L),
                withId(PayoutCandidateItem.ofSaleFee(2001L, 20L, 600L), 5L)
        );
        given(payoutQueryRepository.findUnsettledSellerIds(from, to, PayoutPolicy.PAYOUT_BATCH_PAYEE_SIZE)).willReturn(sellerIds);
        given(payoutQueryRepository.findUnsettledCandidates(sellerIds, from, to)).willReturn(candidates);
        // Act
        int settled = payoutService.settle(from, to, 100);

        // Assert
        assertThat(settled).isEqualTo(2);   // 정산한 판매자 수 (시스템은 판매자 수에 안 셈)
        verify(payoutRepository).saveAll(payoutsCaptor.capture()); // saveAll이 1번 불렸는지 검증 + 넘어간 리스트 꺼내기
        assertThat(payoutsCaptor.getValue())
                .extracting(Payout::getPayeeId, Payout::isSystem, Payout::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(10L, false, 12_700L),   // 판매자 10 : 대금 9,700 + 배송비 3,000
                        tuple(20L, false, 19_400L),   // 판매자 20 : 대금 19,400 (배송비 없음)
                        tuple(null, true, 900L)       // 시스템 : 수수료 300 + 600 (회원 수취인 없음)
                );
        // 이벤트는 판매자 정산 2건만 발행 (시스템 몫은 정산에 기록)
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())   // payoutId·createdAt은 저장 후 채워지는 값이라 단위 테스트에서는 검증 안 함
                .extracting(SellerPayoutCreatedEvent::payeeId, SellerPayoutCreatedEvent::amount)
                .containsExactlyInAnyOrder(
                        tuple(10L, 12_700L),
                        tuple(20L, 19_400L));
    }

    @Test
    @DisplayName("정산할 판매자가 없으면 0을 반환하고 아무것도 저장하지 않음")
    void returnsZeroAndSavesNothing_whenNoUnsettledSellers() {
        // Arrange: 판매자 조회 결과가 빈 리스트 (given 없이도 Mock 기본값은 빈 리스트지만, 의도를 드러내려고 명시)
        LocalDateTime from = LocalDateTime.of(2026, 9, 21, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 25, 0, 0);
        given(payoutQueryRepository.findUnsettledSellerIds(from, to, 100)).willReturn(List.of());

        // Act
        int settled = payoutService.settle(from, to, 100);

        // Assert
        assertThat(settled).isZero();                                          // 0 → tasklet이 FINISHED로 종료
        verify(payoutQueryRepository, never()).findUnsettledCandidates(any(), any(), any()); // 후보 조회까지 가지 않음
        verify(payoutRepository, never()).saveAll(any());                      // 저장도 안 함
    }

    @Test
    @DisplayName("저장 전(id 없는) 후보로는 정산 항목을 만들 수 없다")
    void throwsException_whenCandidateHasNoId() {
        // Arrange
        LocalDateTime from = LocalDateTime.of(2026, 9, 21, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 25, 0, 0);
        List<Long> sellerIds = List.of(10L);
        given(payoutQueryRepository.findUnsettledSellerIds(from, to, 100)).willReturn(sellerIds);
        given(payoutQueryRepository.findUnsettledCandidates(sellerIds, from, to))
                .willReturn(List.of(PayoutCandidateItem.ofSaleAmount(1001L, 10L, 9_700L)));   // id 없음

        // Act & Assert
        RestApiException e = assertThrows(RestApiException.class, () -> payoutService.settle(from, to, 100));
        assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.MISSING_CANDIDATE_ID);
        verify(payoutRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("조회 상한(to)은 실행일 − 15일 00:00")
    void settlementTo_isRunDateMinusWaitingDays() {
        // Act
        LocalDateTime to = payoutService.settlementTo(LocalDate.of(2026, 10, 10));

        // Assert: 10/10 − 15일 = 09/25 00:00
        assertThat(to).isEqualTo(LocalDateTime.of(2026, 9, 25, 0, 0));
    }

    @Test
    @DisplayName("정산이 한 건도 없으면(첫 실행) 조회 하한(from)은 null")
    void settlementFrom_isNull_whenNoPayoutExists() {
        // Arrange
        given(payoutRepository.findFirstByOrderByIdDesc()).willReturn(Optional.empty());

        // Act & Assert
        assertThat(payoutService.settlementFrom()).isNull();
    }

    @Test
    @DisplayName("조회 하한(from)은 마지막 정산일 − (15일 + 여유 3일) 00:00")
    void settlementFrom_isLastPayoutDateMinusWaitingAndMarginDays() {
        // Arrange: 마지막 정산이 10/09 02:00에 생성됨
        // Payout.forSeller()로 만든 객체는 created_at이 비어 있으므로(저장 시 Auditing이 채움) 가짜 Payout을 사용
        Payout lastPayout = mock(Payout.class);
        given(lastPayout.getCreatedAt()).willReturn(LocalDateTime.of(2026, 10, 9, 2, 0));
        given(payoutRepository.findFirstByOrderByIdDesc()).willReturn(Optional.of(lastPayout));

        // Act
        LocalDateTime from = payoutService.settlementFrom();

        // Assert: 10/09 − 18일 = 09/21 00:00 (시각 02:00은 버리고 날짜 시작으로)
        assertThat(from).isEqualTo(LocalDateTime.of(2026, 9, 21, 0, 0));
    }

    // 저장된 후보처럼 id를 채움 (PayoutItem은 저장된 후보로만 만들 수 있음)
    // ReflectionTestUtils.setField(객체, "필드이름", 값): 스프링 테스트 도구, setter가 없어도 private 필드에 값을 강제로 넣음
    // (PayoutCandidateItem은 setId()가 없고 id는 DB 저장 시 AUTO_INCREMENT로 채워지므로, DB 없는 단위 테스트에서 직접 넣음)
    private PayoutCandidateItem withId(PayoutCandidateItem candidate, Long id) {
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }
}
