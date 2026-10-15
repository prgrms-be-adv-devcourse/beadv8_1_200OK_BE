package com.ok.config;

import com.ok.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

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

    private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemDetailAccessDeniedHandler accessDeniedHandler;

    // 인증 기능을 구현하기 전까지 로그인 페이지 없이 모든 요청을 허용 (인증 구현 시 교체할 것)
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
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
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
     * 요청에서 Bearer 토큰을 꺼낸다. 단, 신원이 필요 없는 로그인·회원가입 요청에서는 토큰을 무시한다.
     *
     * <p>토큰이 있으면 공개 API여도 검증하므로, 만료된 토큰을 들고 다시 로그인하려는 요청이 401로 막힐 수 있다.
     * 로그인·회원가입은 토큰으로 얻을 정보가 없으니 토큰을 읽지 않아, 오래된 토큰이 재로그인을 막지 않게 한다.
     * 토큰 재발급 API가 생기면 같은 이유로 이 목록에 추가한다.
     *
     * <p>컨트롤러 경로와 문자열로 연결되어 있어, 경로가 바뀌면 여기만 어긋날 수 있다.
     * {@code SecurityIntegrationTest}의 "만료 토큰으로 로그인·회원가입" 테스트가 이 어긋남을 잡는다.
     */
    private BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        RequestMatcher tokenIgnoredRequests = new OrRequestMatcher(
                path.matcher(HttpMethod.POST, "/api/v1/auth/login"),
                path.matcher(HttpMethod.POST, "/api/v1/members/signup")
        );
        return request -> tokenIgnoredRequests.matches(request) ? null : delegate.resolve(request);
    }

    /**
     * 토큰의 role claim(예: SELLER)을 Spring Security 권한(ROLE_SELLER)으로 바꾼다.
     * 그래서 각 모듈에서 {@code @PreAuthorize("hasRole('SELLER')")}로 역할을 확인할 수 있다.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName(TokenClaims.ROLE);
        authoritiesConverter.setAuthorityPrefix(ROLE_PREFIX);

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

}
