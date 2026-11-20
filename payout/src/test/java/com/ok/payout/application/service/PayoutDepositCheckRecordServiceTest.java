package com.ok.payout.application.service;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ok.payout.application.port.in.PayoutDepositCheckAnswerCommand;
import com.ok.payout.application.port.out.PayoutDepositCheckRepository;
import com.ok.payout.application.port.out.PayoutOutboxRepository;
import com.ok.payout.domain.PayoutDepositCheck;
import com.ok.payout.domain.PayoutOutbox;
import com.ok.payout.domain.PayoutOutboxStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

// 대사 답 반영 중 "입금 없음" 정산의 outbox 줄 되돌리기 (#620)
// 정상 흐름(되돌림 → 재발송)은 PayoutDepositCheckIntegrationTest가 확인하고, 여기서는 실제 DB로 만들기 어려운 "outbox 줄이 없음" 분기를 확인함
@ExtendWith(MockitoExtension.class)
class PayoutDepositCheckRecordServiceTest {

    private static final Long CHECK_ID = 7L;
    private static final LocalDateTime ASKED_AT = LocalDateTime.of(2026, 11, 15, 12, 0);

    @Mock
    private PayoutDepositCheckRepository payoutDepositCheckRepository;

    @Mock
    private PayoutOutboxRepository payoutOutboxRepository;

    @InjectMocks
    private PayoutDepositCheckRecordService sut;

    @Test
    void 없음_답이_여러_건이고_하나의_outbox_줄이_없어도_나머지_줄은_되돌린다() {
        // given: 정산 15·16번을 같은 질문(7번)으로 물었고 둘 다 "없음" 답. 15번은 보낸 줄, 16번은 outbox 줄이 없음(코드가 틀린 경우)
        PayoutDepositCheck check15 = askedCheck(15L);
        PayoutDepositCheck check16 = askedCheck(16L);
        given(payoutDepositCheckRepository.findAllByPayoutIdIn(anyCollection())).willReturn(List.of(check15, check16));
        PayoutOutbox outbox15 = PayoutOutbox.pending(15L);
        outbox15.markSent(LocalDateTime.of(2026, 11, 15, 2, 5));
        given(payoutOutboxRepository.findAllByPayoutIdIn(anyCollection())).willReturn(List.of(outbox15));
        PayoutDepositCheckAnswerCommand command = new PayoutDepositCheckAnswerCommand(CHECK_ID, List.of(
                new PayoutDepositCheckAnswerCommand.Item(15L, false, null, null, null),
                new PayoutDepositCheckAnswerCommand.Item(16L, false, null, null, null)));

        // when & then: 16번 때문에 예외로 답 묶음 전체가 롤백되지 않음 (16번은 ERROR만 남고 다음 날 대사가 다시 "없음"을 받음)
        assertThatCode(() -> sut.applyAnswer(command)).doesNotThrowAnyException();
        assertThat(outbox15).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(outbox15.getStatus()).isEqualTo(PayoutOutboxStatus.PENDING);
            softly.assertThat(outbox15.getSentAt()).isNull();
            softly.assertThat(check16.getNotFoundCount()).isEqualTo(1);
        });
    }

    // 질문 7번으로 한 번 물은 확인 대기 줄
    private static PayoutDepositCheck askedCheck(Long payoutId) {
        PayoutDepositCheck check = PayoutDepositCheck.waiting(payoutId, 5L, 9_700L);
        check.recordAsked(CHECK_ID, ASKED_AT);
        return check;
    }
}
