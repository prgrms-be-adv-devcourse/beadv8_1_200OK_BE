package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class WithdrawRefundServiceTest {

    private static final Long BUYER_ID = 1L;
    private static final Long OTHER_BUYER_ID = 999L;
    private static final Long SELLER_ID = 5L;
    private static final Long REFUND_ID = 7L;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T01:00:00Z"), SEOUL);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 10, 0);

    @Mock
    RefundPersistencePort refundPersistencePort;

    WithdrawRefundService sut;

    @BeforeEach
    void setUp() {
        sut = new WithdrawRefundService(refundPersistencePort, CLOCK, new NoOpTransactionManager());
    }

    @Test
    void 철회하면_지금_시각으로_철회가_된다() {
        // given
        Refund refund = requested();
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

        // when
        RefundResult result = sut.withdraw(REFUND_ID, BUYER_ID);

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo("WITHDRAWN");
            softly.assertThat(result.decidedAt()).isEqualTo(NOW);
        });
        // 명시적 저장(#481): update 호출과 넘긴 값 자체가 요구사항이다. 진행 중 칸을 비워 저장해야 같은 상품을 다시 요청할 수 있다
        Refund updated = capturedUpdate();
        assertSoftly(softly -> {
            softly.assertThat(updated.getStatus()).isEqualTo(RefundStatus.WITHDRAWN);
            softly.assertThat(updated.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(updated.getActiveOrderItemId()).isNull();
        });
    }

    @Test
    void 남의_요청을_철회하면_없는_요청처럼_막고_바꾸지_않는다() {
        // given
        Refund refund = requested();
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.of(refund));

        // when & then
        assertThatThrownBy(() -> sut.withdraw(REFUND_ID, OTHER_BUYER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        then(refundPersistencePort).should(never()).update(any());
    }

    @Test
    void 없는_요청을_철회하면_막는다() {
        // given
        given(refundPersistencePort.findByIdForUpdate(REFUND_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.withdraw(REFUND_ID, BUYER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_NOT_FOUND));
    }

    private static Refund requested() {
        return Refund.request(10L, 101L, 2, BUYER_ID, SELLER_ID, 55L, "감자", "1kg", 10_000L, null);
    }

    // update에 넘긴 환불. 정확히 한 번 불러야 한다(두 번이면 상태 변경 경로가 꼬인 것이다)
    private Refund capturedUpdate() {
        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        then(refundPersistencePort).should().update(captor.capture());
        Refund updated = captor.getValue();
        assertThat(updated).isNotNull();
        return updated;
    }
}
