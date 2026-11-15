package com.ok.wallet.application.service;

import com.ok.wallet.application.dto.PayoutDepositCheckCommand;
import com.ok.wallet.application.dto.PayoutDepositCheckResult;
import com.ok.wallet.application.dto.SellerLedgerDepositResult;
import com.ok.wallet.application.port.out.PayoutDepositCheckResultPublisher;
import com.ok.wallet.application.port.out.SellerWalletLedgerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class WalletPayoutDepositCheckServiceTest {
    private static final LocalDateTime DEPOSITED_AT = LocalDateTime.of(2026, 11, 15, 2, 0, 3);

    @Mock
    SellerWalletLedgerRepository sellerWalletLedgerRepository;

    @Mock
    PayoutDepositCheckResultPublisher payoutDepositCheckResultPublisher;

    @InjectMocks
    WalletPayoutDepositCheckService payoutDepositCheckService;

    @Test
    void 원장에_있는_정산은_찾음으로_없는_정산은_못_찾음으로_요청_순서대로_답한다() {
        // given
        given(sellerWalletLedgerRepository.findDepositsByReferenceKeys(any()))
                .willReturn(List.of(new SellerLedgerDepositResult("PAYOUT:15", 5L, 9_700L, DEPOSITED_AT)));

        // when
        payoutDepositCheckService.check(new PayoutDepositCheckCommand(3L, List.of(16L, 15L)));

        // then
        PayoutDepositCheckResult sut = publishedResult();
        assertThat(sut.checkId()).isEqualTo(3L);
        assertThat(sut.items())
                .extracting(PayoutDepositCheckResult.Item::payoutId, PayoutDepositCheckResult.Item::found,
                        PayoutDepositCheckResult.Item::payeeId, PayoutDepositCheckResult.Item::amount,
                        PayoutDepositCheckResult.Item::depositedAt)
                .containsExactly(
                        tuple(16L, false, null, null, null),
                        tuple(15L, true, 5L, 9_700L, DEPOSITED_AT));
    }

    @Test
    void 같은_정산이_묶음에_두_번_있으면_한_번만_조회하고_둘_다_답한다() {
        // given
        given(sellerWalletLedgerRepository.findDepositsByReferenceKeys(List.of("PAYOUT:15")))
                .willReturn(List.of(new SellerLedgerDepositResult("PAYOUT:15", 5L, 9_700L, DEPOSITED_AT)));

        // when
        payoutDepositCheckService.check(new PayoutDepositCheckCommand(3L, List.of(15L, 15L)));

        // then
        assertThat(publishedResult().items())
                .extracting(PayoutDepositCheckResult.Item::payoutId, PayoutDepositCheckResult.Item::found)
                .containsExactly(tuple(15L, true), tuple(15L, true));
    }

    // 정산과 맞춘 묶음 크기(500건)를 넘어도 거절하지 않고, 조회 조건이 한없이 길어지지 않게 나눠 읽는 것이 요구사항이라 호출을 센다
    @Test
    void 조회_한도를_넘는_묶음은_나눠_읽고_모든_정산에_답한다() {
        // given
        int size = WalletPayoutDepositCheckService.QUERY_CHUNK_SIZE * 2 + 1;
        List<Long> payoutIds = LongStream.rangeClosed(1, size).boxed().toList();
        List<Collection<String>> queriedChunks = new ArrayList<>();
        given(sellerWalletLedgerRepository.findDepositsByReferenceKeys(any())).willAnswer(invocation -> {
            queriedChunks.add(List.copyOf(invocation.<Collection<String>>getArgument(0)));
            return List.of();
        });

        // when
        payoutDepositCheckService.check(new PayoutDepositCheckCommand(3L, payoutIds));

        // then
        assertThat(queriedChunks).extracting(Collection::size)
                .containsExactly(WalletPayoutDepositCheckService.QUERY_CHUNK_SIZE, WalletPayoutDepositCheckService.QUERY_CHUNK_SIZE, 1);
        assertThat(publishedResult().items()).hasSize(size);
    }

    @Test
    void 정산_ID가_없는_항목이_남아_있으면_조회하지_않고_멈춘다() {
        // given
        PayoutDepositCheckCommand command = new PayoutDepositCheckCommand(3L, Arrays.asList(15L, null));

        // when & then
        assertThatThrownBy(() -> payoutDepositCheckService.check(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("정산 ID가 없는 항목이 남아 있습니다. checkId=3");
        then(sellerWalletLedgerRepository).should(never()).findDepositsByReferenceKeys(any());
        then(payoutDepositCheckResultPublisher).should(never()).publish(any());
    }

    @Test
    void 답을_보내지_못하면_예외를_그대로_올린다() {
        // given
        given(sellerWalletLedgerRepository.findDepositsByReferenceKeys(any())).willReturn(List.of());
        IllegalStateException sendFailure = new IllegalStateException("보내기 실패");
        willThrow(sendFailure).given(payoutDepositCheckResultPublisher).publish(any());
        PayoutDepositCheckCommand command = new PayoutDepositCheckCommand(3L, List.of(15L));

        // when & then
        assertThatThrownBy(() -> payoutDepositCheckService.check(command))
                .isSameAs(sendFailure);
    }

    private PayoutDepositCheckResult publishedResult() {
        ArgumentCaptor<PayoutDepositCheckResult> captor = ArgumentCaptor.forClass(PayoutDepositCheckResult.class);
        then(payoutDepositCheckResultPublisher).should(times(1)).publish(captor.capture());
        return captor.getValue();
    }
}
