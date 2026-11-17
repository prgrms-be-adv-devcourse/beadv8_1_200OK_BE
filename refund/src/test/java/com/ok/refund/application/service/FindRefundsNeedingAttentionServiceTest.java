package com.ok.refund.application.service;

import com.ok.refund.application.port.out.RefundStepPersistencePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

// 지갑 결과 대기의 "오래됨" 기준(30분 전)을 지금 시각으로 계산해 넘기는지, 찾은 수를 돌려주는지 본다(#577)
@ExtendWith(MockitoExtension.class)
class FindRefundsNeedingAttentionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 15, 10, 0);

    @Mock
    RefundStepPersistencePort refundStepPersistencePort;

    FindRefundsNeedingAttentionService sut;

    @BeforeEach
    void setUp() {
        sut = new FindRefundsNeedingAttentionService(refundStepPersistencePort, CLOCK, new NoOpTransactionManager());
    }

    @Test
    void 지금보다_30분_전을_기준으로_찾고_찾은_수를_돌려준다() {
        // given: 알릴 환불 12건(로그에는 앞 10건만 적는다)
        List<Long> found = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        given(refundStepPersistencePort.findRefundIdsNeedingAttention(NOW.minusMinutes(30))).willReturn(found);

        // when
        int count = sut.findAndReport();

        // then
        assertThat(count).isEqualTo(12);
    }

    @Test
    void 찾은_환불이_없으면_0을_돌려준다() {
        // given
        given(refundStepPersistencePort.findRefundIdsNeedingAttention(NOW.minusMinutes(30))).willReturn(List.of());

        // when
        int count = sut.findAndReport();

        // then
        assertThat(count).isZero();
    }
}
