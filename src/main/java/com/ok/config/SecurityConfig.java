package com.ok.config;

import com.ok.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.net.InetAddress;
import java.util.List;

/**
 * 요청마다 AccessToken(JWT)을 검증해 회원을 인증한다.
 *
 * <p>인증은 이 설정 한 곳에서 처리하고, 인가는 각 모듈이 자기 API에 {@code @PreAuthorize}로 선언한다.
 * 중앙 설정이 모든 모듈의 URL을 알지 않기 위해서다.
 *
 * <p>토큰 검증에 쓰는 {@code JwtDecoder} 빈은 auth의 JWT 설정이 등록한다.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String ROLE_PREFIX = "ROLE_";

    /**
     * 토큰을 읽지 않는 요청 (HTTP 메서드 + 경로 패턴).
     * 컨트롤러 경로와 문자열로 연결되어 있어, 경로를 바꾸면 여기도 함께 바꾼다.
     * 하위 경로(예: 리뷰의 /me)까지 열리지 않도록 경로를 하나씩 지정한다. ({@link #bearerTokenResolver()} 참고)
     */
    private static final List<Endpoint> TOKEN_IGNORED_ENDPOINTS = List.of(
            // 신원이 필요 없는 인증 요청
            new Endpoint(HttpMethod.POST, "/api/v1/auth/login"),
            new Endpoint(HttpMethod.POST, "/api/v1/members/signup"),
            new Endpoint(HttpMethod.POST, "/api/v1/auth/reissue"),
            // 누구나 볼 수 있는 상품 조회
            new Endpoint(HttpMethod.GET, "/api/v1/products"),
            new Endpoint(HttpMethod.GET, "/api/v1/products/{productId}"),
            new Endpoint(HttpMethod.GET, "/api/v1/products/categories")
    );

    private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemDetailAccessDeniedHandler accessDeniedHandler;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                // 토큰으로 매 요청을 인증하므로 서버에 세션을 만들지 않는다
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // TODO(#108): 각 모듈이 토큰 기반으로 전환한 뒤, 공개 API만 열고 나머지는 authenticated()로 기본 거부한다.
                //  지금 바로 거부하면 아직 헤더로 회원을 받는 API와 그 테스트가 모두 401로 깨진다.
                //  토큰이 있으면 permitAll이어도 검증하므로(위조·만료 토큰은 401), 전환 기간 동안 두 방식이 함께 동작한다.
                //  전환 전까지 헤더(X-Member-Id)로 회원을 받는 API는 다른 회원을 사칭할 수 있으므로, 운영 배포 전에 반드시 닫는다.
                .authorizeHttpRequests(auth -> auth
                        // 내부 API는 인증 없이 호출하므로 같은 컨테이너(loopback)에서 온 요청만 받는다.
                        // 내부 호출은 localhost:8080으로 자기 자신을 부르고, nginx를 거친 요청은 docker 네트워크 주소에서 온다.
                        // 그래서 app.internal-api.base-url을 nginx 주소로 바꾸면 내부 호출이 막힌다.
                        .requestMatchers("/internal/**").access(loopbackOnly())
                        .anyRequest().permitAll())
                // 진입점·처리기를 두 곳에 지정하는 것은 중복이 아니다. 적용되는 실패가 다르다.
                //  - oauth2ResourceServer: Bearer 토큰 검증 실패 (만료·위조 토큰)
                //  - exceptionHandling: 토큰 없이 들어온 익명 요청의 접근 거부, 로그인한 회원의 권한 부족
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * 요청에서 Bearer 토큰을 꺼낸다. 단, 신원이 필요 없는 요청에서는 토큰을 무시한다.
     *
     * <p>토큰이 있으면 공개 API여도 검증하므로, 만료된 토큰이 붙은 요청은 401로 막힌다.
     * 신원이 필요 없는 요청까지 막히지 않도록 토큰을 읽지 않는다.
     * <ul>
     *   <li>로그인·회원가입·토큰 재발급</li>
     *   <li>상품 조회</li>
     * </ul>
     *
     * <p>여기 있는 요청은 토큰을 아예 읽지 않으므로 {@code @CurrentMemberId}를 쓸 수 없다(항상 401).
     * 로그인 여부에 따라 응답이 달라져야 하는 API는 이 목록에 넣지 않는다.
     *
     * <p>이 목록은 "토큰을 읽지 않는 요청"이지 "로그인 없이 허용하는 요청"이 아니다.
     * 기본 거부로 바꿀 때 공개 API 목록은 {@code authorizeHttpRequests}에 따로 둔다.
     */
    private BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        RequestMatcher tokenIgnoredRequests = toMatcher(TOKEN_IGNORED_ENDPOINTS);
        return request -> tokenIgnoredRequests.matches(request) ? null : delegate.resolve(request);
    }

    /**
     * 요청 목록을 하나의 매처로 묶는다. 목록의 요청 중 하나라도 맞으면 일치한다.
     */
    private static RequestMatcher toMatcher(List<Endpoint> endpoints) {
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        List<RequestMatcher> matchers = endpoints.stream()
                .map(endpoint -> (RequestMatcher) path.matcher(endpoint.method(), endpoint.pattern()))
                .toList();
        return new OrRequestMatcher(matchers);
    }

    /**
     * 토큰의 roles claim(예: ["BUYER", "SELLER"])의 각 값을 Spring Security 권한(ROLE_BUYER, ROLE_SELLER)으로 바꾼다.
     * 그래서 각 모듈에서 {@code @PreAuthorize("hasRole('SELLER')")}로 역할을 확인할 수 있다.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName(TokenClaims.ROLES);
        authoritiesConverter.setAuthorityPrefix(ROLE_PREFIX);

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    /**
     * 같은 컨테이너(loopback)에서 온 요청만 허용한다.
     * {@code getRemoteAddr()}는 실제 소켓 주소라서 {@code X-Forwarded-For} 헤더로 바꿀 수 없다.
     * 이 판단이 위조되지 않도록 {@code server.forward-headers-strategy}를 켜지 않는다.
     */
    private static AuthorizationManager<RequestAuthorizationContext> loopbackOnly() {
        return (authentication, context) -> new AuthorizationDecision(isLoopback(context.getRequest().getRemoteAddr()));
    }

    private static boolean isLoopback(String remoteAddr) {
        try {
            // IP 문자열만 해석해 DNS 조회가 일어나지 않게 한다
            return InetAddress.ofLiteral(remoteAddr).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 요청 매칭에 쓰는 HTTP 메서드와 경로 패턴 한 쌍. */
    private record Endpoint(HttpMethod method, String pattern) {
    }

}
