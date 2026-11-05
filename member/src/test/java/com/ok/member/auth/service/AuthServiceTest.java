package com.ok.member.auth.service;

import com.ok.common.exception.RestApiException;
import com.ok.common.security.TokenRoles;
import com.ok.member.auth.domain.AccessToken;
import com.ok.member.auth.domain.IssuedRefreshToken;
import com.ok.member.auth.exception.AuthErrorCode;
import com.ok.member.auth.service.dto.TokenReissueCommand;
import com.ok.member.auth.service.dto.TokenResult;
import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberStatus;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.seller.service.SellerApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");
    private static final String ENCODED_PASSWORD = "{bcrypt}encoded";
    private static final Long MEMBER_ID = 1L;

    @Mock
    MemberRepository memberRepository;

    @Mock
    AccessTokenProvider accessTokenProvider;

    @Mock
    RefreshTokenService refreshTokenService;

    // 기본값 false라 별도 stub이 없는 테스트는 판매자가 아닌 회원(BUYER)으로 다룬다
    @Mock
    SellerApplicationService sellerApplicationService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(memberRepository, accessTokenProvider, refreshTokenService,
                sellerApplicationService);
    }

    @Test
    void 토큰을_발급하면_Access_Token과_Refresh_Token을_함께_반환한다() {
        // given
        Instant accessExpiresAt = NOW.plus(Duration.ofMinutes(30));
        Instant refreshExpiresAt = NOW.plus(Duration.ofDays(14));
        given(accessTokenProvider.issue(MEMBER_ID, List.of(TokenRoles.BUYER)))
                .willReturn(new AccessToken("access-token", accessExpiresAt));
        given(refreshTokenService.issue(MEMBER_ID))
                .willReturn(new IssuedRefreshToken("refresh-token", refreshExpiresAt));

        // when
        TokenResult sut = authService.issueTokens(member(MemberStatus.ACTIVE));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.accessToken()).isEqualTo("access-token");
            softly.assertThat(sut.accessTokenExpiresAt()).isEqualTo(accessExpiresAt);
            softly.assertThat(sut.refreshToken()).isEqualTo("refresh-token");
            softly.assertThat(sut.refreshTokenExpiresAt()).isEqualTo(refreshExpiresAt);
        });
    }

    @Test
    void 승인된_판매자에게는_구매자와_판매자_권한으로_Access_Token을_발급한다() {
        // given
        given(sellerApplicationService.isApprovedSeller(MEMBER_ID)).willReturn(true);
        given(accessTokenProvider.issue(MEMBER_ID, List.of(TokenRoles.BUYER, TokenRoles.SELLER)))
                .willReturn(new AccessToken("seller-access-token", NOW.plus(Duration.ofMinutes(30))));
        given(refreshTokenService.issue(MEMBER_ID))
                .willReturn(new IssuedRefreshToken("refresh-token", NOW.plus(Duration.ofDays(14))));

        // when
        TokenResult sut = authService.issueTokens(member(MemberStatus.ACTIVE));

        // then
        assertThat(sut.accessToken()).isEqualTo("seller-access-token");
    }

    @Test
    void 재발급하면_토큰을_사용_처리하고_회원의_현재_권한으로_새_토큰을_발급한다() {
        // given
        given(refreshTokenService.consume("old-refresh-token")).willReturn(MEMBER_ID);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member(MemberStatus.ACTIVE)));

        Instant accessExpiresAt = NOW.plus(Duration.ofMinutes(30));
        Instant refreshExpiresAt = NOW.plus(Duration.ofDays(14));
        given(accessTokenProvider.issue(MEMBER_ID, List.of(TokenRoles.BUYER)))
                .willReturn(new AccessToken("new-access-token", accessExpiresAt));
        given(refreshTokenService.issue(MEMBER_ID))
                .willReturn(new IssuedRefreshToken("new-refresh-token", refreshExpiresAt));

        // when
        TokenResult sut = authService.reissue(new TokenReissueCommand("old-refresh-token"));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.accessToken()).isEqualTo("new-access-token");
            softly.assertThat(sut.accessTokenExpiresAt()).isEqualTo(accessExpiresAt);
            softly.assertThat(sut.refreshToken()).isEqualTo("new-refresh-token");
            softly.assertThat(sut.refreshTokenExpiresAt()).isEqualTo(refreshExpiresAt);
        });
    }

    @Test
    void 재발급할_때_판매자_승인_여부를_다시_조회해_승인됐으면_판매자_권한을_함께_준다() {
        // given: 승인 전에 로그인해 BUYER 토큰만 가진 회원이 승인 뒤 재발급한다
        given(refreshTokenService.consume("old-refresh-token")).willReturn(MEMBER_ID);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member(MemberStatus.ACTIVE)));
        given(sellerApplicationService.isApprovedSeller(MEMBER_ID)).willReturn(true);
        given(accessTokenProvider.issue(MEMBER_ID, List.of(TokenRoles.BUYER, TokenRoles.SELLER)))
                .willReturn(new AccessToken("seller-access-token", NOW.plus(Duration.ofMinutes(30))));
        given(refreshTokenService.issue(MEMBER_ID))
                .willReturn(new IssuedRefreshToken("new-refresh-token", NOW.plus(Duration.ofDays(14))));

        // when
        TokenResult sut = authService.reissue(new TokenReissueCommand("old-refresh-token"));

        // then
        assertThat(sut.accessToken()).isEqualTo("seller-access-token");
    }

    @Test
    void 재발급할_때_회원이_없으면_INVALID_REFRESH_TOKEN이_발생하고_새_토큰을_발급하지_않는다() {
        // given
        given(refreshTokenService.consume("old-refresh-token")).willReturn(MEMBER_ID);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> authService.reissue(new TokenReissueCommand("old-refresh-token")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
        then(refreshTokenService).should(never()).issue(any());
    }

    @ParameterizedTest
    @MethodSource("재발급할_수_없는_회원_상태")
    void 재발급할_수_없는_상태의_회원은_예외가_발생하고_새_토큰을_발급하지_않는다(
            MemberStatus status, AuthErrorCode expected) {
        // given
        given(refreshTokenService.consume("old-refresh-token")).willReturn(MEMBER_ID);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member(status)));

        // when & then
        assertThatThrownBy(() -> authService.reissue(new TokenReissueCommand("old-refresh-token")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        then(refreshTokenService).should(never()).issue(any());
    }

    static Stream<Arguments> 재발급할_수_없는_회원_상태() {
        return Stream.of(
                // 탈퇴 회원은 로그인과 같이 "없는 회원"으로 취급한다
                Arguments.of(MemberStatus.WITHDRAWN, AuthErrorCode.INVALID_REFRESH_TOKEN),
                Arguments.of(MemberStatus.SUSPENDED, AuthErrorCode.MEMBER_SUSPENDED)
        );
    }

    private Member member(MemberStatus status) {
        Member member = Member.createUser("tester01", ENCODED_PASSWORD, "tester@example.com",
                "홍길동", "길동이", LocalDateTime.of(2026, 10, 1, 12, 0));
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        // 정지·탈퇴로 바꾸는 도메인 메서드가 아직 없어 상태를 직접 설정한다
        ReflectionTestUtils.setField(member, "status", status);
        return member;
    }

}
