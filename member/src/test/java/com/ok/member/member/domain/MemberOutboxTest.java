package com.ok.member.member.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class MemberOutboxTest {

    @Test
    void 회원가입_줄은_보내기_전_상태로_만들어진다() {
        // when
        MemberOutbox sut = MemberOutbox.memberRegistered(1L);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getEventType()).isEqualTo(MemberOutboxEventType.MEMBER_REGISTERED);
            softly.assertThat(sut.getMemberId()).isEqualTo(1L);
            softly.assertThat(sut.getStatus()).isEqualTo(MemberOutboxStatus.PENDING);
            softly.assertThat(sut.getAttemptCount()).isZero();
            softly.assertThat(sut.getSentAt()).isNull();
        });
    }

    @Test
    void 보냄으로_바꾸면_보낸_시각을_남긴다() {
        // given
        MemberOutbox sut = MemberOutbox.memberRegistered(1L);
        LocalDateTime sentAt = LocalDateTime.of(2026, 11, 15, 10, 0);

        // when
        sut.markSent(sentAt);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(MemberOutboxStatus.SENT);
            softly.assertThat(sut.getSentAt()).isEqualTo(sentAt);
        });
    }

    @Test
    void 실패를_기록하면_시도_횟수가_늘고_다시_보내도록_보내기_전_상태로_남는다() {
        // given
        MemberOutbox sut = MemberOutbox.memberRegistered(1L);

        // when
        sut.recordFailure("첫 실패");
        sut.recordFailure("두 번째 실패");

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getAttemptCount()).isEqualTo(2);
            softly.assertThat(sut.getLastError()).isEqualTo("두 번째 실패");
            softly.assertThat(sut.getStatus()).isEqualTo(MemberOutboxStatus.PENDING);
        });
    }

    @Test
    void 실패_원인이_500자를_넘으면_잘라_저장한다() {
        // given: 길이 제한을 넘으면 저장이 실패해 같은 줄이 매번 실패한다
        MemberOutbox sut = MemberOutbox.memberRegistered(1L);

        // when
        sut.recordFailure("가".repeat(MemberOutbox.MAX_ERROR_LENGTH + 1));

        // then
        assertThat(sut.getLastError()).hasSize(MemberOutbox.MAX_ERROR_LENGTH);
    }
}
