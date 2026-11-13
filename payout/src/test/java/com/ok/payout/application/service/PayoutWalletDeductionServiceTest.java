package com.ok.payout.application.service;

import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.payout.domain.PayoutWalletDeductionConflict;
import com.ok.payout.application.port.out.PayoutWalletDeductionConflictRepository;
import com.ok.payout.application.port.out.PayoutWalletDeductionRepository;
import com.ok.payout.application.port.in.WalletPaymentCompletedCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 단위 테스트: Spring 없이, 리포지토리는 Mockito 가짜 객체로 대체
@ExtendWith(MockitoExtension.class)
class PayoutWalletDeductionServiceTest {

    // 주문 ID와 구매자 ID는 일부러 다른 값. 같은 값이면 두 필드의 자리가 바뀌어도 티가 안 남
    private static final Long ORDER_ID = 100L;
    private static final Long BUYER_ID = 5L;
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 10, 19, 14, 0);

    @Mock
    private PayoutWalletDeductionRepository repository;

    @Mock
    private PayoutWalletDeductionConflictRepository conflictRepository;

    @InjectMocks
    private PayoutWalletDeductionService sut;

    // save()에 넘어간 차감 기록을 꺼낼 공간
    @Captor
    private ArgumentCaptor<PayoutWalletDeduction> deductionCaptor;

    // save()에 넘어간 충돌 기록을 꺼낼 공간
    @Captor
    private ArgumentCaptor<PayoutWalletDeductionConflict> conflictCaptor;

    @Test
    void 처음_받은_지갑_차감_이벤트는_값을_그대로_저장한다() {
        // given
        WalletPaymentCompletedCommand command = new WalletPaymentCompletedCommand(ORDER_ID, BUYER_ID, 13_000L, PAID_AT);
        given(repository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());

        // when
        sut.saveDeduction(command);

        // then
        verify(repository).save(deductionCaptor.capture());
        PayoutWalletDeduction saved = deductionCaptor.getValue();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getOrderId()).isEqualTo(ORDER_ID);
            softly.assertThat(saved.getBuyerId()).isEqualTo(BUYER_ID);
            softly.assertThat(saved.getAmount()).isEqualTo(13_000L);
            softly.assertThat(saved.getDeductedAt()).isEqualTo(PAID_AT);
        });
        verify(conflictRepository, never()).save(any());
    }

    @Test
    void 이미_저장된_주문의_같은_금액_지갑_차감_이벤트는_예외_없이_아무것도_저장하지_않는다() {
        // given: 같은 주문·같은 금액의 차감 기록이 이미 있음 (이벤트 재전달 상황)
        WalletPaymentCompletedCommand command = new WalletPaymentCompletedCommand(ORDER_ID, BUYER_ID, 13_000L, PAID_AT);
        given(repository.findByOrderId(ORDER_ID)).willReturn(Optional.of(storedDeduction(13_000L)));

        // when & then
        // 중복 이벤트는 오류가 아니라 이미 처리된 건 → 예외 없이 조용히 끝나야 함 (멱등)
        assertThatCode(() -> sut.saveDeduction(command)).doesNotThrowAnyException();
        verify(repository, never()).save(any());
        verify(conflictRepository, never()).save(any());
    }

    @Test
    void 이미_저장된_주문에_다른_금액이_오면_충돌을_1건_기록하고_차감_기록은_바꾸지_않는다() {
        // given: 15,000으로 저장된 주문에 20,000 이벤트
        WalletPaymentCompletedCommand command = new WalletPaymentCompletedCommand(ORDER_ID, BUYER_ID, 20_000L, PAID_AT);
        given(repository.findByOrderId(ORDER_ID)).willReturn(Optional.of(storedDeduction(15_000L)));
        given(conflictRepository.existsByOrderIdAndReceivedAmount(ORDER_ID, 20_000L)).willReturn(false);

        // when
        sut.saveDeduction(command);

        // then: 충돌에는 저장된 금액과 받은 금액이 둘 다 남고, 차감 기록은 새로 저장하지 않음
        verify(conflictRepository).save(conflictCaptor.capture());
        PayoutWalletDeductionConflict saved = conflictCaptor.getValue();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getOrderId()).isEqualTo(ORDER_ID);
            softly.assertThat(saved.getStoredAmount()).isEqualTo(15_000L);
            softly.assertThat(saved.getReceivedAmount()).isEqualTo(20_000L);
        });
        verify(repository, never()).save(any());
    }

    @Test
    void 이미_기록된_같은_금액_충돌이_다시_오면_다시_저장하지_않는다() {
        // given: 15,000으로 저장된 주문에 20,000 충돌이 이미 기록됨 (Kafka 재전달 상황)
        WalletPaymentCompletedCommand command = new WalletPaymentCompletedCommand(ORDER_ID, BUYER_ID, 20_000L, PAID_AT);
        given(repository.findByOrderId(ORDER_ID)).willReturn(Optional.of(storedDeduction(15_000L)));
        given(conflictRepository.existsByOrderIdAndReceivedAmount(ORDER_ID, 20_000L)).willReturn(true);

        // when & then
        assertThatCode(() -> sut.saveDeduction(command)).doesNotThrowAnyException();
        verify(conflictRepository, never()).save(any());
        verify(repository, never()).save(any());
    }

    // 이미 저장되어 있는 차감 기록
    private PayoutWalletDeduction storedDeduction(long amount) {
        return PayoutWalletDeduction.create(ORDER_ID, BUYER_ID, amount, PAID_AT);
    }
}
