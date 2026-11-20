package com.ok.member.member.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.application.dto.MemberAuthAccount;
import com.ok.member.member.application.dto.MemberInfoUpdateCommand;
import com.ok.member.member.application.dto.MemberLoginCredential;
import com.ok.member.member.application.port.out.MemberRepository;
import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberStatus;
import com.ok.member.member.exception.MemberErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    MemberRepository memberRepository;

    MemberService memberService;

    @BeforeEach
    void setUp() {
        memberService = new MemberService(memberRepository, CLOCK);
    }

    @Test
    void 확인한_해시가_그대로면_비밀번호를_바꾸고_토큰_버전을_올린다() {
        // given
        Member sut = member(MemberStatus.ACTIVE);
        given(memberRepository.findForUpdateById(MEMBER_ID)).willReturn(Optional.of(sut));

        // when
        memberService.changePassword(MEMBER_ID, "{bcrypt}encoded", "{bcrypt}new");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getPassword()).isEqualTo("{bcrypt}new");
            softly.assertThat(sut.getTokenVersion()).isEqualTo(1L);
            softly.assertThat(sut.getTokenVersionChangedAt()).isEqualTo(LocalDateTime.now(CLOCK));
        });
    }

    @Test
    void 확인한_뒤_다른_요청이_비밀번호를_먼저_바꿨으면_PASSWORD_CHANGED_CONCURRENTLY를_던지고_바꾸지_않는다() {
        // given: 이 요청이 bcrypt로 확인한 해시와 잠근 뒤 읽은 해시가 다르다
        Member member = member(MemberStatus.ACTIVE);
        given(memberRepository.findForUpdateById(MEMBER_ID)).willReturn(Optional.of(member));

        // when & then
        assertThatThrownBy(() -> memberService.changePassword(MEMBER_ID, "{bcrypt}old", "{bcrypt}new"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.PASSWORD_CHANGED_CONCURRENTLY));
        assertSoftly(softly -> {
            softly.assertThat(member.getPassword()).isEqualTo("{bcrypt}encoded");
            softly.assertThat(member.getTokenVersion()).isZero();
        });
    }

    @Test
    void 탈퇴한_회원이면_비밀번호를_바꾸지_않고_MEMBER_NOT_FOUND를_던진다() {
        // given
        Member member = member(MemberStatus.WITHDRAWN);
        given(memberRepository.findForUpdateById(MEMBER_ID)).willReturn(Optional.of(member));

        // when & then
        assertThatThrownBy(() -> memberService.changePassword(MEMBER_ID, "{bcrypt}encoded", "{bcrypt}new"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
        assertThat(member.getTokenVersion()).isZero();
    }

    @Test
    void 회원이_없으면_조회할_때_MEMBER_NOT_FOUND를_던진다() {
        // given
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> memberService.getMyInfo(MEMBER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    @Test
    void 탈퇴한_회원이면_조회할_때_MEMBER_NOT_FOUND를_던진다() {
        // given
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member(MemberStatus.WITHDRAWN)));

        // when & then
        assertThatThrownBy(() -> memberService.getMyInfo(MEMBER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    @Test
    void 탈퇴한_회원이면_수정하지_않고_MEMBER_NOT_FOUND를_던진다() {
        // given
        Member member = member(MemberStatus.WITHDRAWN);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));

        // when & then
        assertThatThrownBy(() -> memberService.updateMyInfo(MEMBER_ID, new MemberInfoUpdateCommand("이서연", "주말장보기")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
        assertThat(member.getNickname()).isEqualTo("길동이");
    }

    @Test
    void 로그인_정보는_탈퇴한_회원도_비밀번호_해시와_함께_돌려준다() {
        // given: 틀린 비밀번호에 회원 상태와 관계없이 같은 응답을 주려면, 상태는 비밀번호를 확인한 뒤 판단해야 한다
        given(memberRepository.findByLoginId("tester01")).willReturn(Optional.of(member(MemberStatus.WITHDRAWN)));

        // when
        Optional<MemberLoginCredential> sut = memberService.findLoginCredential("tester01");

        // then
        assertThat(sut).contains(new MemberLoginCredential(MEMBER_ID, "{bcrypt}encoded"));
    }

    @ParameterizedTest
    @MethodSource("회원_상태별_계정_상태")
    void 계정_상태는_회원_도메인이_판단한_탈퇴_정지_여부로_돌려준다(MemberStatus status, boolean withdrawn, boolean suspended) {
        // given
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member(status)));

        // when
        Optional<MemberAuthAccount> sut = memberService.findAuthAccount(MEMBER_ID);

        // then
        assertThat(sut).contains(new MemberAuthAccount(MEMBER_ID, false, withdrawn, suspended, "{bcrypt}encoded", 0L));
    }

    static Stream<Arguments> 회원_상태별_계정_상태() {
        return Stream.of(
                Arguments.of(MemberStatus.ACTIVE, false, false),
                Arguments.of(MemberStatus.SUSPENDED, false, true),
                Arguments.of(MemberStatus.WITHDRAWN, true, false)
        );
    }

    @Test
    void 관리자의_계정_상태는_관리자로_돌려준다() {
        // given
        Member admin = Member.createAdmin("admin01", "{bcrypt}encoded", "admin@example.com",
                "관리자", "관리자00", LocalDateTime.of(2026, 10, 19, 12, 0));
        ReflectionTestUtils.setField(admin, "id", MEMBER_ID);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(admin));

        // when
        Optional<MemberAuthAccount> sut = memberService.findAuthAccount(MEMBER_ID);

        // then
        assertThat(sut).hasValueSatisfying(account -> assertThat(account.admin()).isTrue());
    }

    private Member member(MemberStatus status) {
        Member member = Member.createUser("tester01", "{bcrypt}encoded", "tester@example.com",
                "홍길동", "길동이", LocalDateTime.of(2026, 10, 19, 12, 0));
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        ReflectionTestUtils.setField(member, "status", status);
        return member;
    }

}
