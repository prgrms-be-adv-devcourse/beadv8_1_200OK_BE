package com.ok.member.auth.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 4, 0);

    @Mock
    RefreshTokenService refreshTokenService;

    RefreshTokenCleanupService refreshTokenCleanupService;

    @BeforeEach
    void setUp() {
        refreshTokenCleanupService = new RefreshTokenCleanupService(refreshTokenService,
                Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
    }

    @Test
    void 묶음이_가득_차면_다음_묶음을_지우고_덜_차면_멈춘다() {
        // given
        given(refreshTokenService.deleteExpiredBatch(NOW, 3)).willReturn(3, 3, 1);

        // when
        int sut = refreshTokenCleanupService.deleteExpired(3);

        // then
        // 모든 묶음이 처음에 정한 같은 기준 시각으로 지운다
        assertThat(sut).isEqualTo(7);
        then(refreshTokenService).should(times(3)).deleteExpiredBatch(NOW, 3);
    }

    @Test
    void 마지막_묶음이_정확히_가득_차면_빈_묶음을_한_번_더_확인하고_멈춘다() {
        // given
        given(refreshTokenService.deleteExpiredBatch(NOW, 2)).willReturn(2, 0);

        // when
        int sut = refreshTokenCleanupService.deleteExpired(2);

        // then
        assertThat(sut).isEqualTo(2);
        then(refreshTokenService).should(times(2)).deleteExpiredBatch(NOW, 2);
    }

    @ParameterizedTest
    @MethodSource("잘못된_묶음_크기")
    void 묶음_크기가_1보다_작으면_지우지_않는다(int batchSize) {
        // when & then
        assertThatThrownBy(() -> refreshTokenCleanupService.deleteExpired(batchSize))
                .isInstanceOf(IllegalArgumentException.class);
        then(refreshTokenService).should(never()).deleteExpiredBatch(any(), anyInt());
    }

    static Stream<Integer> 잘못된_묶음_크기() {
        return Stream.of(0, -1);
    }

}
