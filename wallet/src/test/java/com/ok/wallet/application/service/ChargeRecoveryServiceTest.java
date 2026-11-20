package com.ok.wallet.application.service;

import com.ok.wallet.application.dto.ChargeConfirmResult;
import com.ok.wallet.application.dto.ChargeRecoveryResult;
import com.ok.wallet.application.dto.TossPaymentLookupResult;
import com.ok.wallet.application.dto.TossPaymentStatus;
import com.ok.wallet.application.port.out.TossPaymentsClient;
import com.ok.wallet.domain.Charge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

// 맡기·멈춤 처리의 DB 오류는 실제 DB로 일으키기 어려워, 충전 건 트랜잭션을 Mock으로 바꿔 확인한다
@ExtendWith(MockitoExtension.class)
class ChargeRecoveryServiceTest {
    private static final long AMOUNT = 10_000;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    ChargeTransactionService chargeTransaction;

    @Mock
    TossPaymentsClient tossPaymentsClient;

    ChargeRecoveryService chargeRecoveryService;

    @BeforeEach
    void setUp() {
        chargeRecoveryService = new ChargeRecoveryService(chargeTransaction, tossPaymentsClient, CLOCK);
    }

    @Test
    void 한_건의_멈춤_처리가_DB_오류로_실패해도_다음_건은_처리한다() {
        // given: 첫 건은 열 번째 확인이고 결제 키가 다른 승인 실패라 멈춰야 하는데, 멈춤 처리가 DB 오류를 낸다.
        // 반영 중 오류로 보고 다시 멈추려다 같은 오류가 또 난다
        Charge broken = charge("CHARGE-broken");
        given(broken.getPaymentKey()).willReturn("broken-key");
        given(broken.getCheckCount()).willReturn(9);
        Charge normal = completableCharge("CHARGE-normal");
        given(chargeTransaction.findDueForRecovery(anyInt())).willReturn(List.of(broken, normal));
        given(chargeTransaction.claimForRecovery("CHARGE-broken")).willReturn(true);
        given(chargeTransaction.claimForRecovery("CHARGE-normal")).willReturn(true);
        given(tossPaymentsClient.findPayment("CHARGE-broken"))
                .willReturn(new TossPaymentLookupResult.Found("other-key", TossPaymentStatus.ABORTED, AMOUNT));
        willThrow(new DataAccessResourceFailureException("DB 연결 끊김"))
                .given(chargeTransaction).holdForReview("CHARGE-broken");

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.completed()).isEqualTo(1);
            softly.assertThat(sut.retried()).isEqualTo(1);
            softly.assertThat(sut.held()).isZero();
        });
    }

    @Test
    void 맡기가_DB_오류로_실패한_건은_토스에_묻지_않고_세지_않으며_다음_건은_처리한다() {
        // given
        Charge broken = charge("CHARGE-broken");
        Charge normal = completableCharge("CHARGE-normal");
        given(chargeTransaction.findDueForRecovery(anyInt())).willReturn(List.of(broken, normal));
        willThrow(new DataAccessResourceFailureException("DB 연결 끊김"))
                .given(chargeTransaction).claimForRecovery("CHARGE-broken");
        given(chargeTransaction.claimForRecovery("CHARGE-normal")).willReturn(true);

        // when
        ChargeRecoveryResult sut = chargeRecoveryService.recover();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.completed()).isEqualTo(1);
            softly.assertThat(sut.checked()).isEqualTo(1);
        });
        then(tossPaymentsClient).should(never()).findPayment("CHARGE-broken");
    }

    // 토스가 같은 결제 키·금액으로 승인했다고 답하고 완료되는 건
    private Charge completableCharge(String chargeNo) {
        Charge charge = charge(chargeNo);
        given(charge.getMemberId()).willReturn(2L);
        given(charge.getPaymentKey()).willReturn("normal-key");
        given(charge.hasAmount(AMOUNT)).willReturn(true);
        given(tossPaymentsClient.findPayment(chargeNo))
                .willReturn(new TossPaymentLookupResult.Found("normal-key", TossPaymentStatus.DONE, AMOUNT));
        given(chargeTransaction.complete(2L, chargeNo, "normal-key"))
                .willReturn(new ChargeConfirmResult(chargeNo, "COMPLETED", AMOUNT));
        return charge;
    }

    private static Charge charge(String chargeNo) {
        Charge charge = mock(Charge.class);
        given(charge.getChargeNo()).willReturn(chargeNo);
        return charge;
    }
}
