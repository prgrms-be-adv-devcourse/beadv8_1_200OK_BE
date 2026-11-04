package com.ok.common.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code jwt.jwk-set-uri}를 지정했을 때 디코더가 발급 모듈의 JWKS에서 공개키를 받아 검증하는지 확인한다.
 *
 * <p>Spring을 띄우지 않고 JDK 내장 HTTP 서버로 가짜 JWKS를 응답한다. JWKS 호출은 디코더 내부(Nimbus)에서 일어나
 * 우리 인터페이스 뒤에 둘 수 없으므로, 실제 HTTP 호출로 캐시·재조회·타임아웃·예외 변환까지 확인한다.
 */
class JwtConfigTest {

    private static final String ISSUER = "paldo-gotgan";
    private static final String AUDIENCE = "paldo-gotgan-api";
    private static final String JWKS_PATH = "/.well-known/jwks.json";
    private static final Duration ACCESS_EXPIRATION = Duration.ofMinutes(30);
    private static final Duration REFRESH_EXPIRATION = Duration.ofDays(14);

    private final JwtConfig jwtConfig = new JwtConfig();
    private final Clock clock = Clock.systemUTC();
    private final AtomicInteger jwksRequests = new AtomicInteger();
    // 응답을 막아 두는 테스트가 끝날 때 핸들러 스레드를 풀어 서버를 바로 멈출 수 있게 한다
    private final CountDownLatch release = new CountDownLatch(1);

    private HttpServer server;
    private volatile List<RSAKey> publishedKeys = new ArrayList<>();
    private volatile Duration responseDelay = Duration.ZERO;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(JWKS_PATH, exchange -> {
            jwksRequests.incrementAndGet();
            if (!responseDelay.isZero()) {
                try {
                    release.await(responseDelay.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = new JWKSet(new ArrayList<JWK>(publishedKeys)).toString(true).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void 원격_JWKS의_공개키로_서명한_토큰을_검증한다() throws Exception {
        // given
        RSAKey v1 = rsaKey("rs256-v1");
        publishedKeys = List.of(v1.toPublicJWK());
        JwtDecoder decoder = jwtConfig.jwtDecoder(remoteProperties(), clock);

        // when
        Jwt sut = decoder.decode(sign(v1));

        // then
        assertThat(sut.getSubject()).isEqualTo("1");
    }

    @Test
    void 캐시_시간_안에는_JWKS를_다시_받지_않는다() throws Exception {
        // given
        RSAKey v1 = rsaKey("rs256-v1");
        publishedKeys = List.of(v1.toPublicJWK());
        JwtDecoder decoder = jwtConfig.jwtDecoder(remoteProperties(), clock);
        String token = sign(v1);

        // when
        decoder.decode(token);
        decoder.decode(token);

        // then
        assertThat(jwksRequests).hasValue(1);
    }

    @Test
    void 캐시_시간이_짧아도_디코더가_만들어지고_만료_뒤_요청에서_JWKS를_다시_받는다() throws Exception {
        // given
        // 미리 갱신을 켜 두면 캐시 시간이 30초 + 타임아웃보다 짧을 때 디코더를 만들지 못한다
        RSAKey v1 = rsaKey("rs256-v1");
        publishedKeys = List.of(v1.toPublicJWK());
        JwtProperties.JwkSet shortTtl = new JwtProperties.JwkSet(Duration.ofMillis(300), null, null);
        JwtDecoder decoder = jwtConfig.jwtDecoder(remoteProperties(shortTtl), clock);
        String token = sign(v1);
        decoder.decode(token);
        Thread.sleep(500);

        // when
        decoder.decode(token);

        // then
        assertThat(jwksRequests).hasValue(2);
    }

    @Test
    void JWKS에_새_키를_공개하면_디코더를_다시_만들지_않아도_새_kid로_서명한_토큰을_검증한다() throws Exception {
        // given
        // 키 교체: 검증 앱이 v1만 캐시한 상태에서 발급 모듈이 v2를 공개하고 v2로 서명을 바꾼다
        RSAKey v1 = rsaKey("rs256-v1");
        RSAKey v2 = rsaKey("rs256-v2");
        publishedKeys = List.of(v1.toPublicJWK());
        JwtDecoder decoder = jwtConfig.jwtDecoder(remoteProperties(), clock);
        decoder.decode(sign(v1));
        publishedKeys = List.of(v1.toPublicJWK(), v2.toPublicJWK());

        // when
        // 캐시 시간(5분)이 남아 있어도 처음 보는 kid라 바로 다시 받아 온다
        Jwt sut = decoder.decode(sign(v2));

        // then
        assertThat(sut.getHeaders()).containsEntry("kid", "rs256-v2");
    }

    @Test
    void JWKS에_없는_kid로_서명한_토큰은_토큰_오류로_거부된다() throws Exception {
        // given
        RSAKey v1 = rsaKey("rs256-v1");
        RSAKey unknown = rsaKey("rs256-unknown");
        publishedKeys = List.of(v1.toPublicJWK());
        JwtDecoder decoder = jwtConfig.jwtDecoder(remoteProperties(), clock);

        // when & then
        // 토큰 오류(BadJwtException)는 401로 응답된다
        assertThatThrownBy(() -> decoder.decode(sign(unknown)))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void JWKS_서버가_꺼져_있어도_디코더가_만들어진다() {
        // given
        // 앱이 뜰 때 JWKS를 부르지 않아야 발급 모듈 없이도 다른 모듈 앱이 기동한다
        server.stop(0);

        // when & then
        assertThatCode(() -> jwtConfig.jwtDecoder(remoteProperties(), clock))
                .doesNotThrowAnyException();
    }

    @Test
    void JWKS를_받을_수_없으면_토큰_오류가_아닌_예외가_발생한다() throws Exception {
        // given
        RSAKey v1 = rsaKey("rs256-v1");
        JwtDecoder decoder = jwtConfig.jwtDecoder(remoteProperties(), clock);
        String token = sign(v1);
        server.stop(0);

        // when & then
        // 토큰 오류(BadJwtException)가 아니므로 401이 아닌 서버 쪽 오류로 처리된다
        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtException.class)
                .isNotInstanceOf(BadJwtException.class);
    }

    @Test
    void JWKS_응답이_타임아웃을_넘기면_기다리지_않고_토큰_오류가_아닌_예외가_발생한다() throws Exception {
        // given
        RSAKey v1 = rsaKey("rs256-v1");
        publishedKeys = List.of(v1.toPublicJWK());
        responseDelay = Duration.ofSeconds(5);
        JwtProperties.JwkSet shortTimeout = new JwtProperties.JwkSet(null, Duration.ofMillis(200), Duration.ofMillis(200));
        JwtDecoder decoder = jwtConfig.jwtDecoder(remoteProperties(shortTimeout), clock);
        String token = sign(v1);
        Instant start = Instant.now();

        // when & then
        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtException.class)
                .isNotInstanceOf(BadJwtException.class);
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void JWKS_주소가_있으면_로컬_공개키_파일을_읽지_않는다() throws Exception {
        // given
        // 분리 배포한 앱에는 키 파일이 없지만 모듈 yaml의 로컬 기본값은 남아 있다
        RSAKey v1 = rsaKey("rs256-v1");
        publishedKeys = List.of(v1.toPublicJWK());
        JwtProperties properties = new JwtProperties(ISSUER, AUDIENCE, jwkSetUri(),
                List.of(new JwtProperties.VerificationKey("rs256-v1", new FileSystemResource("no-such-dir/rs256-v1-public.pem"))),
                null, ACCESS_EXPIRATION, REFRESH_EXPIRATION);

        // when
        JwtDecoder decoder = jwtConfig.jwtDecoder(properties, clock);

        // then
        assertThat(decoder.decode(sign(v1)).getSubject()).isEqualTo("1");
    }

    private JwtProperties remoteProperties() {
        return remoteProperties(null);
    }

    private JwtProperties remoteProperties(JwtProperties.JwkSet jwkSet) {
        return new JwtProperties(ISSUER, AUDIENCE, jwkSetUri(), null, jwkSet, ACCESS_EXPIRATION, REFRESH_EXPIRATION);
    }

    private String jwkSetUri() {
        return "http://127.0.0.1:%d%s".formatted(server.getAddress().getPort(), JWKS_PATH);
    }

    private static RSAKey rsaKey(String keyId) throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(keyId)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
    }

    /**
     * 발급 모듈과 같은 헤더(RS256, at+jwt, kid)와 검증에 필요한 클레임으로 토큰을 만든다.
     */
    private static String sign(RSAKey signingKey) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("1")
                .audience(List.of(AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_EXPIRATION))
                .build();
        JwsHeader header = JwsHeader.with(JwtConfig.SIGNING_ALGORITHM)
                .type(JwtConfig.TOKEN_TYPE)
                .keyId(signingKey.getKeyID())
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)))
                .encode(JwtEncoderParameters.from(header, claims))
                .getTokenValue();
    }

}
