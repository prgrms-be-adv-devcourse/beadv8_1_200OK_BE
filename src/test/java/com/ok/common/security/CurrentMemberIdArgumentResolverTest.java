package com.ok.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CurrentMemberIdArgumentResolverTest {

    private final CurrentMemberIdArgumentResolver resolver = new CurrentMemberIdArgumentResolver();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 토큰_인증이면_subject를_회원_id로_반환한다() throws Exception {
        // given
        authenticate(jwtAuthentication("1"));

        // when
        Long sut = resolver.resolveArgument(parameter("annotatedLong"), null, null, null);

        // then
        assertThat(sut).isEqualTo(1L);
    }

    @ParameterizedTest
    @MethodSource("토큰_인증이_아닌_인증")
    void 토큰_인증이_아니면_인증_필요_예외가_발생한다(Authentication authentication) {
        // given
        authenticate(authentication);

        // when & then
        assertThatThrownBy(() -> resolver.resolveArgument(parameter("annotatedLong"), null, null, null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessage("로그인이 필요합니다.");
    }

    static Stream<Authentication> 토큰_인증이_아닌_인증() {
        return Stream.of(
                null,
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
        );
    }

    @Test
    void subject가_숫자가_아니면_잘못된_인증_예외가_발생한다() {
        // given
        authenticate(jwtAuthentication("not-a-number"));

        // when & then
        assertThatThrownBy(() -> resolver.resolveArgument(parameter("annotatedLong"), null, null, null))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("토큰의 회원 식별자가 올바르지 않습니다.");
    }

    @ParameterizedTest
    @MethodSource("지원하는_파라미터")
    void 어노테이션이_붙은_Long과_long_파라미터는_처리한다(String methodName) throws Exception {
        // when
        boolean sut = resolver.supportsParameter(parameter(methodName));

        // then
        assertThat(sut).isTrue();
    }

    static Stream<String> 지원하는_파라미터() {
        return Stream.of("annotatedLong", "annotatedPrimitiveLong");
    }

    @Test
    void 어노테이션이_붙은_Long_파라미터는_처리한다() throws Exception {
        // when
        boolean sut = resolver.supportsParameter(parameter("annotatedLong"));

        // then
        assertThat(sut).isTrue();
    }


    @Test
    void 어노테이션이_없는_파라미터는_처리하지_않는다() throws Exception {
        // when
        boolean sut = resolver.supportsParameter(parameter("notAnnotated"));

        // then
        assertThat(sut).isFalse();
    }

    @Test
    void Long이_아닌_타입에_붙이면_조용히_넘어가지_않고_예외가_발생한다() throws Exception {
        // given
        MethodParameter parameter = parameter("annotatedString");

        // when & then
        assertThatThrownBy(() -> resolver.supportsParameter(parameter))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void subject가_없으면_잘못된_인증_예외가_발생한다() {
        // given
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("roles", java.util.List.of("BUYER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        authenticate(new JwtAuthenticationToken(jwt));

        // when & then
        assertThatThrownBy(() -> resolver.resolveArgument(parameter("annotatedLong"), null, null, null))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("토큰에 회원 식별자가 없습니다.");
    }

    private void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private JwtAuthenticationToken jwtAuthentication(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    private MethodParameter parameter(String methodName) throws NoSuchMethodException {
        Class<?> parameterType = switch (methodName) {
            case "annotatedString" -> String.class;
            case "annotatedPrimitiveLong" -> long.class;
            default -> Long.class;
        };
        Method method = SampleController.class.getDeclaredMethod(methodName, parameterType);
        return new MethodParameter(method, 0);
    }

    @SuppressWarnings("unused")
    static class SampleController {

        void annotatedLong(@CurrentMemberId Long memberId) {
        }

        void notAnnotated(Long memberId) {
        }

        void annotatedString(@CurrentMemberId String memberId) {
        }

        void annotatedPrimitiveLong(@CurrentMemberId long memberId) {
        }

    }

}
