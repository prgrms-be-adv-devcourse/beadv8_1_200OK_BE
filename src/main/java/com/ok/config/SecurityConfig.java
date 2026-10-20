package com.ok.config;

import com.ok.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
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
     * 로그인 없이 허용하는 API (HTTP 메서드 + 경로 패턴 + 토큰 처리 방식). 여기 없는 API는 토큰이 없으면 401이다.
     * 공개 API와 토큰을 읽지 않는 API를 한 목록에 두어, 공개 API를 추가할 때 한 곳만 고치면 되게 한다.
     * 컨트롤러 경로와 문자열로 연결되어 있어, 경로를 바꾸면 여기도 함께 바꾼다.
     * 하위 경로(예: 리뷰의 /me)까지 열리지 않도록 경로를 하나씩 지정한다.
     */
    private static final List<PublicEndpoint> PUBLIC_ENDPOINTS = List.of(
            // 신원이 필요 없는 인증 요청
            new PublicEndpoint(HttpMethod.POST, "/api/v1/auth/login", TokenUse.IGNORED),
            new PublicEndpoint(HttpMethod.POST, "/api/v1/members/signup", TokenUse.IGNORED),
            new PublicEndpoint(HttpMethod.POST, "/api/v1/auth/reissue", TokenUse.IGNORED),
            // 누구나 볼 수 있는 상품·리뷰 조회
            new PublicEndpoint(HttpMethod.GET, "/api/v1/products", TokenUse.IGNORED),
            new PublicEndpoint(HttpMethod.GET, "/api/v1/products/{productId}", TokenUse.IGNORED),
            new PublicEndpoint(HttpMethod.GET, "/api/v1/products/categories", TokenUse.IGNORED),
            new PublicEndpoint(HttpMethod.GET, "/api/v1/products/{productId}/reviews", TokenUse.IGNORED),
            // 저장하지 않는 배송비·도착 예정일 계산이라 로그인 전에도 쓴다
            new PublicEndpoint(HttpMethod.POST, "/api/v1/shipments/fee-quotes", TokenUse.IGNORED)
    );

    /**
     * API가 아니지만 로그인 없이 허용하는 경로. 외부에는 nginx가 /api/v1만 공개하므로 이 경로들은 밖에서 닿지 않는다.
     */
    private static final String[] PUBLIC_PATHS = {
            // 예외를 응답으로 바꾸는 오류 경로. 막으면 실제 오류(404 등) 대신 401이 나가 원인을 가린다
            "/error",
            // 컨테이너 헬스체크. compose와 deploy.sh가 토큰 없이 호출한다
            "/actuator/health"
    };

    /**
     * API 문서(Swagger) 경로. 운영 프로필이 아닐 때만 로그인 없이 허용한다.
     * 운영에서는 springdoc도 꺼져 있지만, 설정이 실수로 켜져도 문서가 공개되지 않도록 여기서도 막는다.
     */
    private static final String[] API_DOCS_PATHS = {"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**"};

    private static final String PROD_PROFILE = "prod";

    private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemDetailAccessDeniedHandler accessDeniedHandler;
    private final Environment environment;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                // 토큰으로 매 요청을 인증하므로 서버에 세션을 만들지 않는다
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 공개 API·공개 경로·내부 API만 따로 다루고 나머지는 로그인을 요구한다(기본 거부).
                // 새 API는 따로 설정하지 않아도 로그인이 필요하다. 역할 제한은 여기서 하지 않고 각 모듈이 @PreAuthorize로 선언한다
                .authorizeHttpRequests(auth -> auth
                        // 내부 API는 인증 없이 호출하므로 같은 컨테이너(loopback)에서 온 요청만 받는다.
                        // 내부 호출은 localhost:8080으로 자기 자신을 부르고, nginx를 거친 요청은 docker 네트워크 주소에서 온다.
                        // 그래서 app.internal-api.base-url을 nginx 주소로 바꾸면 내부 호출이 막힌다.
                        .requestMatchers("/internal/**").access(loopbackOnly())
                        .requestMatchers(toMatcher(PUBLIC_ENDPOINTS)).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(API_DOCS_PATHS).access(apiDocsAccess())
                        .anyRequest().authenticated())
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
     * 요청에서 Bearer 토큰을 꺼낸다. 단, 토큰을 읽지 않는 공개 API({@link TokenUse#IGNORED})에서는 토큰을 무시한다.
     *
     * <p>토큰이 있으면 공개 API여도 검증하므로, 만료된 토큰이 붙은 요청은 401로 막힌다.
     * 신원이 필요 없는 요청까지 막히지 않도록 토큰을 읽지 않는다.
     */
    private BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        RequestMatcher tokenIgnoredRequests = toMatcher(PUBLIC_ENDPOINTS.stream()
                .filter(endpoint -> endpoint.tokenUse() == TokenUse.IGNORED)
                .toList());
        return request -> tokenIgnoredRequests.matches(request) ? null : delegate.resolve(request);
    }

    /**
     * 요청 목록을 하나의 매처로 묶는다. 목록의 요청 중 하나라도 맞으면 일치한다.
     */
    private static RequestMatcher toMatcher(List<PublicEndpoint> endpoints) {
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
     * API 문서 경로의 접근 규칙. 운영 프로필이면 다른 API처럼 로그인을 요구하고, 그 밖의 프로필(개발·테스트)에서는 허용한다.
     */
    private AuthorizationManager<RequestAuthorizationContext> apiDocsAccess() {
        if (environment.matchesProfiles(PROD_PROFILE)) {
            return AuthenticatedAuthorizationManager.authenticated();
        }
        return (authentication, context) -> new AuthorizationDecision(true);
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

    /** 로그인 없이 허용하는 API 하나. 요청 매칭에 쓰는 HTTP 메서드·경로 패턴과, 붙어 온 토큰을 다루는 방식. */
    private record PublicEndpoint(HttpMethod method, String pattern, TokenUse tokenUse) {
    }

    /** 공개 API에 토큰이 붙어 왔을 때 다루는 방식. */
    private enum TokenUse {

        /**
         * 토큰을 읽지 않는다. 신원이 전혀 필요 없는 공개 API에 쓴다.
         * 만료된 토큰이 붙어 와도 401로 막히지 않지만, 토큰을 아예 읽지 않아 {@code @CurrentMemberId}를 쓸 수 없다.
         */
        IGNORED,

        /**
         * 토큰이 있으면 검증한다. 로그인 여부에 따라 응답이 달라지는 공개 API에 쓴다(예: 로그인했으면 찜 여부를 함께 보여 줌).
         * 만료된 토큰이 붙어 오면 401이다.
         */
        VERIFIED_IF_PRESENT
    }

}
