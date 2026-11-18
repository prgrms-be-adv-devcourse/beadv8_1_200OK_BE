package com.ok.member.member.application.service;

import com.ok.member.member.application.dto.TokenVersionChange;
import com.ok.member.member.application.port.out.TokenVersionRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class TokenVersionResyncFacadeTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 17, 11, 29);
    private static final LocalDateTime RECENT = LocalDateTime.of(2026, 11, 17, 11, 50);
    private static final LocalDateTime OLDER = LocalDateTime.of(2026, 11, 17, 11, 40);

    @Mock
    MemberService memberService;

    @Mock
    TokenVersionRecorder tokenVersionRecorder;

    @Mock
    TokenVersionRecordPolicy tokenVersionRecordPolicy;

    @InjectMocks
    TokenVersionResyncFacade tokenVersionResyncFacade;

    @Test
    void 최근에_토큰_버전을_올린_회원의_DB_버전을_남은_시간만큼_다시_남긴다() {
        // given
        given(tokenVersionRecordPolicy.resyncFrom()).willReturn(FROM);
        given(memberService.findTokenVersionChangesSince(FROM)).willReturn(List.of(
                new TokenVersionChange(1L, 2L, RECENT),
                new TokenVersionChange(2L, 1L, OLDER)));
        given(tokenVersionRecordPolicy.remainingTtl(RECENT)).willReturn(Optional.of(Duration.ofMinutes(21)));
        given(tokenVersionRecordPolicy.remainingTtl(OLDER)).willReturn(Optional.of(Duration.ofMinutes(11)));

        // when
        int sut = tokenVersionResyncFacade.resync();

        // then
        assertThat(sut).isEqualTo(2);
        then(tokenVersionRecorder).should().record(1L, 2L, Duration.ofMinutes(21));
        then(tokenVersionRecorder).should().record(2L, 1L, Duration.ofMinutes(11));
    }

    @Test
    void 남길_시간이_지난_회원은_건너뛴다() {
        // given: 조회와 계산 사이에 시간이 지나 남길 필요가 없어진 경우
        given(tokenVersionRecordPolicy.resyncFrom()).willReturn(FROM);
        given(memberService.findTokenVersionChangesSince(FROM)).willReturn(List.of(new TokenVersionChange(1L, 2L, FROM)));
        given(tokenVersionRecordPolicy.remainingTtl(FROM)).willReturn(Optional.empty());

        // when
        int sut = tokenVersionResyncFacade.resync();

        // then
        assertThat(sut).isZero();
        then(tokenVersionRecorder).should(never()).record(any(), anyLong(), any());
    }

    @Test
    void 저장소에_접근하지_못하면_남은_회원은_다음_실행에_맡기고_멈춘다() {
        // given: 장애 중에는 건마다 타임아웃만큼 기다리다 실패할 뿐이다
        given(tokenVersionRecordPolicy.resyncFrom()).willReturn(FROM);
        given(memberService.findTokenVersionChangesSince(FROM)).willReturn(List.of(
                new TokenVersionChange(1L, 2L, RECENT),
                new TokenVersionChange(2L, 1L, OLDER)));
        given(tokenVersionRecordPolicy.remainingTtl(RECENT)).willReturn(Optional.of(Duration.ofMinutes(21)));
        willThrow(new QueryTimeoutException("Redis 응답 없음"))
                .given(tokenVersionRecorder).record(1L, 2L, Duration.ofMinutes(21));

        // when
        int sut = tokenVersionResyncFacade.resync();

        // then
        assertThat(sut).isZero();
        then(tokenVersionRecorder).should(never()).record(eq(2L), anyLong(), any());
    }

}
