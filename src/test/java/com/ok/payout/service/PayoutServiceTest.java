package com.ok.payout.service;

import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;


// 단위 테스트: Spring 없이, 리포지토리는 Mockito 가짜 객체로 대체
// DB가 필요한 검증(기간 조건, NOT EXISTS, 반복 실행)은 PayoutSettleIntegrationTest에서 확인
@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

    @Mock
    private PayoutQueryRepository payoutQueryRepository;

    @Mock
    private PayoutRepository payoutRepository;

    @InjectMocks
    private PayoutService payoutService;

    // saveAll에 넘어간 Payout 목록을 붙잡아서 검증할 때 사용
    @Captor
    private ArgumentCaptor<List<Payout>> payoutsCaptor;


    @Test
    @DisplayName("판매자 Payout 2건 + 시스템 Payout 1건이 생기고, 금액이 맞음")
    void  createsSellerAndSystemPayouts_whenCandidatesExist() {//ai 물어봐서 이름은 그걸고하자.
        // Arrange
        LocalDateTime from = LocalDateTime.of(2026, 9, 21, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 25, 0, 0);
        List<Long> sellerIds = List.of(10L, 20L);
        List<PayoutCandidateItem> candidates = List.of(
                // 판매자 10: 판매가 10,000 / 배송비 3,000
                PayoutCandidateItem.ofSaleAmount(1001L, 10L, 9_700L),
                PayoutCandidateItem.ofSaleFee(1001L, 10L, 300L),
                PayoutCandidateItem.ofSaleShippingFee(1001L, 10L, 3_000L),
                // 판매자 20: 판매가 20,000 / 배송비 없음
                PayoutCandidateItem.ofSaleAmount(2001L, 20L, 19_400L),
                PayoutCandidateItem.ofSaleFee(2001L, 20L, 600L)
        );
        given(payoutQueryRepository.findUnsettledSellerIds(from, to, PayoutPolicy.PAYOUT_BATCH_PAYEE_SIZE)).willReturn(sellerIds);//가짜대본 이렇게 돌려줘라...
        given(payoutQueryRepository.findUnsettledCandidates(sellerIds, from, to)).willReturn(candidates);
        // Act
        int settled = payoutService.settle(from, to, 100);

        // Assert
        assertThat(settled).isEqualTo(2);   // 정산한 판매자 수 (시스템은 판매자 수에 안 셈)
        verify(payoutRepository).saveAll(payoutsCaptor.capture()); // saveAll이 1번 불렸는지 검증 + 넘어간 리스트 꺼내기
        assertThat(payoutsCaptor.getValue())   // List<Payout> : 저장하려던 Payout 목록
                .extracting(Payout::getPayeeId, Payout::getAmount)  // 각 Payout에서 (수취인 ID, 금액)만 꺼냄
                .containsExactlyInAnyOrder(        // 순서 상관없이 정확히 이 3건이어야 함 (더 많거나 적으면 실패)
                        tuple(10L, 12_700L),      // 판매자 10 : 대금 9,700 + 배송비 3,000
                        tuple(20L, 19_400L),    // 판매자 20 : 대금 19,400 (배송비 없음)
                        tuple(PayoutPolicy.SYSTEM_PAYEE_ID, 900L)  // 시스템 : 수수료 300 + 600
                );
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
        // Payout.create()로 만든 객체는 created_at이 비어 있으므로(저장 시 Auditing이 채움) 가짜 Payout을 사용
        Payout lastPayout = mock(Payout.class);
        given(lastPayout.getCreatedAt()).willReturn(LocalDateTime.of(2026, 10, 9, 2, 0));
        given(payoutRepository.findFirstByOrderByIdDesc()).willReturn(Optional.of(lastPayout));

        // Act
        LocalDateTime from = payoutService.settlementFrom();

        // Assert: 10/09 − 18일 = 09/21 00:00 (시각 02:00은 버리고 날짜 시작으로)
        assertThat(from).isEqualTo(LocalDateTime.of(2026, 9, 21, 0, 0));
    }
}
