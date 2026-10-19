package com.ok.payout.service;

import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.payout.repository.PayoutWalletDeductionRepository;
import com.ok.payout.service.dto.WalletPaymentCompletedCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

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

    @InjectMocks
    private PayoutWalletDeductionService sut;

    // save()에 넘어간 차감 기록을 꺼낼 공간
    @Captor
    private ArgumentCaptor<PayoutWalletDeduction> deductionCaptor;

    @Test
    void 처음_받은_지갑_차감_이벤트는_값을_그대로_저장한다() {
        // given
        WalletPaymentCompletedCommand command = new WalletPaymentCompletedCommand(ORDER_ID, BUYER_ID, 13_000L, PAID_AT);
        given(repository.existsByOrderId(ORDER_ID)).willReturn(false);

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
    }

    @Test
    void 이미_저장된_주문의_지갑_차감_이벤트는_예외_없이_저장하지_않는다() {
        // given: 같은 주문의 차감 기록이 이미 있음 (이벤트 재전달 상황)
        WalletPaymentCompletedCommand command = new WalletPaymentCompletedCommand(ORDER_ID, BUYER_ID, 13_000L, PAID_AT);
        given(repository.existsByOrderId(ORDER_ID)).willReturn(true);

        // when & then
        // 중복 이벤트는 오류가 아니라 이미 처리된 건 → 예외 없이 조용히 끝나야 함 (멱등)
        assertThatCode(() -> sut.saveDeduction(command)).doesNotThrowAnyException();
        verify(repository, never()).save(any());
    }
}
