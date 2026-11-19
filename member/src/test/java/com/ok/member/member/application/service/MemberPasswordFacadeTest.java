package com.ok.member.member.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ok.common.exception.RestApiException;
import com.ok.member.member.application.dto.PasswordChangeCommand;
import com.ok.member.member.application.dto.TokenVersionChange;
import com.ok.member.member.application.port.out.TokenVersionRecorder;
import com.ok.member.member.exception.MemberErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class MemberPasswordFacadeTest {

    private static final Long MEMBER_ID = 1L;
    private static final String CURRENT_HASH = "{bcrypt}current";
    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 11, 17, 12, 0);
    private static final Duration TTL = Duration.ofMinutes(31);

    @Mock
    MemberService memberService;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    TokenVersionRecorder tokenVersionRecorder;

    @Mock
    TokenVersionRecordPolicy tokenVersionRecordPolicy;

    @InjectMocks
    MemberPasswordFacade memberPasswordFacade;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(MemberPasswordFacade.class);

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void 현재_비밀번호가_맞으면_확인에_쓴_해시로_바꾸고_올린_토큰_버전을_저장소에_남긴다() {
        // given: 서비스가 그사이 비밀번호가 바뀌었는지 볼 수 있도록 확인에 쓴 해시를 그대로 넘겨야 한다
        givenPasswordMatches();
        given(memberService.changePassword(MEMBER_ID, CURRENT_HASH, "{bcrypt}new"))
                .willReturn(new TokenVersionChange(MEMBER_ID, 2L, CHANGED_AT));
        given(tokenVersionRecordPolicy.remainingTtl(CHANGED_AT)).willReturn(Optional.of(TTL));

        // when
        memberPasswordFacade.changePassword(MEMBER_ID, new PasswordChangeCommand("password1!", "newPassword1!"));

        // then
        then(tokenVersionRecorder).should().record(MEMBER_ID, 2L, TTL);
    }

    @Test
    void 저장소에_남기지_못해도_비밀번호는_이미_바뀌었으므로_예외_없이_끝난다() {
        // given: 빠진 기록은 다시 채우기가 메운다
        givenPasswordMatches();
        given(memberService.changePassword(MEMBER_ID, CURRENT_HASH, "{bcrypt}new"))
                .willReturn(new TokenVersionChange(MEMBER_ID, 2L, CHANGED_AT));
        given(tokenVersionRecordPolicy.remainingTtl(CHANGED_AT)).willReturn(Optional.of(TTL));
        willThrow(new QueryTimeoutException("Redis 응답 없음")).given(tokenVersionRecorder).record(MEMBER_ID, 2L, TTL);

        // when & then: 예상한 장애라 원인 한 줄만 남기고 스택은 쌓지 않는다
        assertThatCode(() -> memberPasswordFacade.changePassword(MEMBER_ID,
                new PasswordChangeCommand("password1!", "newPassword1!")))
                .doesNotThrowAnyException();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void 저장소_장애가_아닌_예외로_남기지_못해도_커밋된_변경을_실패로_응답하지_않는다() {
        // given: 놓치면 이미 바뀐 비밀번호에 500이 나가 클라이언트가 실패로 알고 다시 시도한다
        givenPasswordMatches();
        given(memberService.changePassword(MEMBER_ID, CURRENT_HASH, "{bcrypt}new"))
                .willReturn(new TokenVersionChange(MEMBER_ID, 2L, CHANGED_AT));
        given(tokenVersionRecordPolicy.remainingTtl(CHANGED_AT)).willReturn(Optional.of(TTL));
        willThrow(new IllegalArgumentException("TTL은 1밀리초 이상이어야 합니다."))
                .given(tokenVersionRecorder).record(MEMBER_ID, 2L, TTL);

        // when & then: 버그라 원인을 찾을 수 있게 스택을 함께 남긴다
        assertThatCode(() -> memberPasswordFacade.changePassword(MEMBER_ID,
                new PasswordChangeCommand("password1!", "newPassword1!")))
                .doesNotThrowAnyException();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalArgumentException.class.getName());
        });
    }

    @Test
    void 비밀번호_변경이_실패하면_저장소에_남기지_않는다() {
        // given: 커밋되지 않은 버전을 남기면 그 회원의 토큰이 모두 거부된다
        givenPasswordMatches();
        given(memberService.changePassword(MEMBER_ID, CURRENT_HASH, "{bcrypt}new"))
                .willThrow(new RestApiException(MemberErrorCode.PASSWORD_CHANGED_CONCURRENTLY));

        // when & then
        assertThatThrownBy(() -> memberPasswordFacade.changePassword(MEMBER_ID,
                new PasswordChangeCommand("password1!", "newPassword1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.PASSWORD_CHANGED_CONCURRENTLY));
        then(tokenVersionRecorder).should(never()).record(any(), anyLong(), any());
    }

    @Test
    void 현재_비밀번호가_틀리면_INVALID_CURRENT_PASSWORD를_던지고_바꾸지_않는다() {
        // given
        given(memberService.findPasswordHash(MEMBER_ID)).willReturn(CURRENT_HASH);
        given(passwordEncoder.matches("wrong1!", CURRENT_HASH)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> memberPasswordFacade.changePassword(MEMBER_ID,
                new PasswordChangeCommand("wrong1!", "newPassword1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.INVALID_CURRENT_PASSWORD));
        then(memberService).should(never()).changePassword(anyLong(), anyString(), anyString());
        then(passwordEncoder).should(never()).encode(any());
        then(tokenVersionRecorder).should(never()).record(any(), anyLong(), any());
    }

    @Test
    void 새_비밀번호가_현재와_같으면_SAME_AS_CURRENT_PASSWORD를_던지고_바꾸지_않는다() {
        // given
        given(memberService.findPasswordHash(MEMBER_ID)).willReturn(CURRENT_HASH);
        given(passwordEncoder.matches("password1!", CURRENT_HASH)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> memberPasswordFacade.changePassword(MEMBER_ID,
                new PasswordChangeCommand("password1!", "password1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.SAME_AS_CURRENT_PASSWORD));
        then(memberService).should(never()).changePassword(anyLong(), anyString(), anyString());
        then(tokenVersionRecorder).should(never()).record(any(), anyLong(), any());
    }

    @Test
    void 현재_비밀번호가_틀리면_새_비밀번호가_같아도_INVALID_CURRENT_PASSWORD가_먼저다() {
        // given: 틀린 현재 비밀번호로 "같은 비밀번호" 오류를 보면, 입력한 값이 현재 비밀번호가 아니라는 것 말고는 알 수 없어야 한다
        given(memberService.findPasswordHash(MEMBER_ID)).willReturn(CURRENT_HASH);
        given(passwordEncoder.matches("wrong1!", CURRENT_HASH)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> memberPasswordFacade.changePassword(MEMBER_ID,
                new PasswordChangeCommand("wrong1!", "wrong1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.INVALID_CURRENT_PASSWORD));
    }

    private void givenPasswordMatches() {
        given(memberService.findPasswordHash(MEMBER_ID)).willReturn(CURRENT_HASH);
        given(passwordEncoder.matches("password1!", CURRENT_HASH)).willReturn(true);
        given(passwordEncoder.encode("newPassword1!")).willReturn("{bcrypt}new");
    }

}
