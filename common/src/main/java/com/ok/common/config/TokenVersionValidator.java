package com.ok.common.config;

import com.ok.common.security.TokenClaims;
import com.ok.common.security.TokenVersionStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 토큰의 버전({@link TokenClaims#TOKEN_VERSION})이 회원의 최소 버전보다 낮으면 거부한다(#561).
 * 비밀번호를 바꾸면 회원 쪽이 최소 버전을 올려 두므로, 그 전에 발급된 Access Token은 만료 전이라도 이 검증기에서 막힌다.
 * 거부 오류는 위조와 같은 {@code invalid_token}이다. 만료({@code token_expired})로 알리면 클라이언트가 재발급을 시도하는데,
 * 같은 세션의 Refresh Token도 이미 거부되므로 바로 재로그인으로 보낸다.
 *
 * <p><b>저장소 장애</b>: 저장소에 접근하지 못하면 거부하지 않고 통과시킨다.
 * 장애를 감지하면 {@link #SKIP_DURATION} 동안 저장소를 부르지 않고 통과시키고, 그 뒤 요청 하나로만 다시 시험해 본다.
 * 그렇게 하지 않으면 장애 동안 인증이 필요한 모든 요청이 Redis 타임아웃(1초)만큼 늦어지고 요청 수만큼 경고가 쌓인다.
 * 상태는 {@code skipUntil} 하나로 나타내고(상태표는 {@link #enter}), 상태를 바꾼 요청만 로그를 남긴다.
 * 상태는 앱 인스턴스마다 따로다. 공유하려면 저장소가 필요한데 그 저장소가 장애 중이기 때문이다.
 *
 * <p>건너뛰는 동안에는 폐기된 토큰도 통과한다. 최대 Access Token 수명(30분)이고, Refresh Token은 DB로 막혀 그 뒤 세션이 끊긴다.
 * 장애 중 회원 DB를 대신 읽는 방법은 서비스를 나누면 모든 서비스가 회원 서비스를 매 요청 부르게 되어 쓰지 않는다(ADR 참고).
 *
 * <p>다른 모듈의 테스트가 {@code JwtConfig#jwtDecoder}를 직접 만들 때 이 타입의 빈 provider를 넘길 수 있도록 클래스만 공개하고, 생성은 {@code JwtConfig}만 한다.
 */
@Slf4j
public class TokenVersionValidator implements OAuth2TokenValidator<Jwt> {

    /** 장애를 감지한 뒤 저장소를 부르지 않는 시간. 길수록 폐기 검사가 빠지는 시간이, 짧을수록 시험 요청이 늦어지는 횟수가 는다 */
    static final Duration SKIP_DURATION = Duration.ofSeconds(10);

    /** 시험 요청이 끝날 때까지 다른 요청이 시험을 다시 잡지 못하게 막는 시간. Redis 타임아웃(1초)보다 길어야 시험이 겹치지 않는다 */
    static final Duration PROBE_HOLD = Duration.ofSeconds(2);

    private static final OAuth2Error REVOKED = new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN, "The access token has been revoked", null);

    private static final OAuth2Error INVALID_VERSION = new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN, "The ver claim is not a number", null);

    private final TokenVersionStore tokenVersionStore;
    private final Clock clock;
    private final AtomicLong skipUntil = new AtomicLong();

    private final Counter passed;
    private final Counter rejected;
    private final Counter skipped;
    private final Counter failed;
    private final Timer lookupTimer;

    TokenVersionValidator(TokenVersionStore tokenVersionStore, Clock clock, MeterRegistry meterRegistry) {
        this.tokenVersionStore = tokenVersionStore;
        this.clock = clock;
        // 장애 중에는 로그를 상태가 바뀔 때만 남기므로, 얼마나 건너뛰었는지는 지표로 본다
        this.passed = counter(meterRegistry, "passed");
        this.rejected = counter(meterRegistry, "rejected");
        this.skipped = counter(meterRegistry, "skipped");
        this.failed = counter(meterRegistry, "error");
        // 실패하지 않고 느리기만 한 저장소는 브레이커가 열리지 않으므로 조회 시간으로 본다
        this.lookupTimer = Timer.builder("token.version.lookup")
                .description("토큰 최소 버전 조회 시간")
                .register(meterRegistry);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Long memberId = memberId(jwt);
        if (memberId == null) {
            // 회원 식별자가 숫자가 아닌 토큰은 인증 변환 단계가 거부한다. 여기서 같은 판단을 두 번 하지 않는다
            return OAuth2TokenValidatorResult.success();
        }
        Object versionClaim = jwt.getClaims().get(TokenClaims.TOKEN_VERSION);
        if (versionClaim != null && !(versionClaim instanceof Number)) {
            return OAuth2TokenValidatorResult.failure(INVALID_VERSION);
        }
        long tokenVersion = versionClaim == null ? 0L : ((Number) versionClaim).longValue();

        long now = clock.millis();
        Lookup lookup = enter(now);
        if (lookup == null) {
            skipped.increment();
            return OAuth2TokenValidatorResult.success();
        }
        OptionalLong minVersion;
        Timer.Sample sample = Timer.start();
        try {
            minVersion = tokenVersionStore.findMinVersion(memberId);
        } catch (DataAccessException e) {
            failed.increment();
            onFailure(lookup, now, e);
            return OAuth2TokenValidatorResult.success();
        } finally {
            sample.stop(lookupTimer);
        }
        onSuccess(lookup);

        if (minVersion.isPresent() && tokenVersion < minVersion.getAsLong()) {
            rejected.increment();
            return OAuth2TokenValidatorResult.failure(REVOKED);
        }
        passed.increment();
        return OAuth2TokenValidatorResult.success();
    }

    /**
     * 이번 요청이 저장소를 불러도 되는지 정한다. {@code skipUntil}의 상태 전이는 아래 세 메서드에만 있다.
     * <pre>
     * 상태(skipUntil)          이번 요청            다음 상태
     * 0 (정상)               → 조회               → 성공: 그대로 0 / 실패: now + 10초 (건너뜀)
     * now보다 뒤 (건너뜀)      → 조회하지 않음(통과)  → 그대로
     * 0이 아니고 now 이하       → CAS에 이긴 1건만 시험 → 시험 중: now + 2초 / 성공: 0 / 실패: now + 10초
     *   (시험할 차례)            나머지는 통과
     * </pre>
     *
     * @return 저장소를 부를 권한. {@code null}이면 부르지 않고 통과시킨다
     */
    private Lookup enter(long now) {
        long until = skipUntil.get();
        if (until == 0) {
            return Lookup.NORMAL;
        }
        if (until > now) {
            return null;
        }
        long probeUntil = now + PROBE_HOLD.toMillis();
        // 시험할 차례에 여러 요청이 와도 CAS에 이긴 하나만 시험을 맡는다
        return skipUntil.compareAndSet(until, probeUntil) ? new Lookup(true, probeUntil) : null;
    }

    // 정상 상태에서 처음 실패한 요청(또는 시험 요청)만 건너뜀으로 바꾸고 경고를 남긴다.
    // 같은 순간 조회하던 다른 요청의 실패는 기대값이 이미 바뀌어 CAS가 실패하므로 상태와 로그를 건드리지 않는다
    private void onFailure(Lookup lookup, long now, DataAccessException e) {
        if (skipUntil.compareAndSet(lookup.ownedState(), now + SKIP_DURATION.toMillis()) && !lookup.probing()) {
            log.warn("토큰 버전 저장소에 접근하지 못해 {}초 동안 토큰 버전 검사를 건너뛴다. causeType={}, cause={}",
                    SKIP_DURATION.toSeconds(), e.getClass().getName(), e.getMessage());
        }
    }

    private void onSuccess(Lookup lookup) {
        if (lookup.probing() && skipUntil.compareAndSet(lookup.ownedState(), 0L)) {
            log.info("토큰 버전 저장소가 응답해 토큰 버전 검사를 다시 시작한다");
        }
    }

    /**
     * 저장소를 부를 권한.
     *
     * @param probing    장애 뒤 시험 요청인지
     * @param ownedState 이 요청이 조회를 시작할 때의 {@code skipUntil} 값. 결과로 상태를 바꿀 때 CAS의 기대값으로 쓴다
     */
    private record Lookup(boolean probing, long ownedState) {
        static final Lookup NORMAL = new Lookup(false, 0L);
    }

    private static Long memberId(Jwt jwt) {
        try {
            return jwt.getSubject() == null ? null : Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Counter counter(MeterRegistry meterRegistry, String result) {
        return Counter.builder("token.version.check")
                .description("토큰 버전 검사 결과")
                .tag("result", result)
                .register(meterRegistry);
    }

}
