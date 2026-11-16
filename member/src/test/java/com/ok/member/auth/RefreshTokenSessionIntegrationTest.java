package com.ok.member.auth;

import com.ok.common.exception.RestApiException;
import com.ok.member.auth.adapter.out.persistence.RefreshTokenJpaRepository;
import com.ok.member.auth.application.dto.LoginCommand;
import com.ok.member.auth.application.dto.LogoutCommand;
import com.ok.member.auth.application.dto.TokenReissueCommand;
import com.ok.member.auth.application.dto.TokenResult;
import com.ok.member.auth.application.port.in.LoginUseCase;
import com.ok.member.auth.application.port.in.LogoutUseCase;
import com.ok.member.auth.application.port.in.RefreshTokenCleanupUseCase;
import com.ok.member.auth.application.port.in.TokenReissueUseCase;
import com.ok.member.auth.application.service.TokenHasher;
import com.ok.member.auth.domain.RefreshToken;
import com.ok.member.auth.exception.AuthErrorCode;
import com.ok.member.member.adapter.out.persistence.MemberJpaRepository;
import com.ok.member.member.domain.Member;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Refresh Token을 세션(행) 단위로 덮어쓰는 방식이 여러 기기와 동시 요청에서 어떻게 동작하는지 실제 MySQL에서 검증한다.
 * 단위 테스트의 Mock으로는 행 잠금, 유니크 키, 교착 상태를 재현할 수 없어 통합 테스트로 확인한다.
 */
class RefreshTokenSessionIntegrationTest extends AbstractIntegrationTest {

    private static final String LOGIN_ID = "session01";
    private static final String PASSWORD = "password1!";

    // 교착 상태나 잠금 대기가 생기면 innodb_lock_wait_timeout(기본 50초)까지 멈추므로, 그보다 짧게 잡아 바로 실패시킨다
    private static final long TIMEOUT_SECONDS = 20;

    // 뒤따르는 요청이 잠금 대기에 들어갈 때까지 기다리는 시간. 테스트 계정에 PROCESS 권한이 없어 INNODB_TRX로 대기를 확인할 수 없다.
    // 느린 CI에서도 대기에 들어가도록 넉넉히 잡는다. 대기에 들어가지 못했을 때의 결과는 테스트마다 주석에 적었다
    private static final long LOCK_WAIT_PAUSE_MILLIS = 1_000;

    @Autowired
    LoginUseCase loginUseCase;

    @Autowired
    TokenReissueUseCase tokenReissueUseCase;

    @Autowired
    LogoutUseCase logoutUseCase;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    RefreshTokenCleanupUseCase refreshTokenCleanup;

    @Autowired
    RefreshTokenJpaRepository refreshTokenRepository;

    @Autowired
    MemberJpaRepository memberRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    Clock clock;

    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 다른_기기에서_다시_로그인해도_기존_기기의_세션은_재발급할_수_있다() {
        // given
        Member member = saveMember();
        TokenResult pc = login();
        TokenResult phone = login();

        // when
        TokenResult sut = tokenReissueUseCase.reissue(new TokenReissueCommand(pc.refreshToken()));

        // then
        // 로그인은 기존 행을 덮어쓰지 않고 세션마다 행을 하나씩 만든다
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.refreshToken()).isNotEqualTo(pc.refreshToken());
            softly.assertThat(sessionsOf(member)).hasSize(2);
            softly.assertThat(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(phone.refreshToken())))
                    .isPresent();
        });
    }

    @Test
    void 같은_회원이_동시에_여러_번_로그인하면_모두_성공하고_세션마다_행이_생긴다() throws Exception {
        // given
        Member member = saveMember();
        int requestCount = 8;

        // when
        List<TokenResult> sut = runConcurrently(requestCount, i -> login());

        // then
        // 무작위 해시끼리는 유니크 키가 겹치지 않아, 같은 회원의 로그인끼리 교착 상태 없이 모두 저장된다
        assertThat(sut).hasSize(requestCount).doesNotContainNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.stream().map(TokenResult::refreshToken).distinct()).hasSize(requestCount);
            softly.assertThat(sessionsOf(member)).hasSize(requestCount);
        });
    }

    @Test
    void 같은_회원의_서로_다른_세션을_동시에_재발급하면_모두_성공하고_행은_늘지_않는다() throws Exception {
        // given
        Member member = saveMember();
        int sessionCount = 8;
        List<String> refreshTokens = IntStream.range(0, sessionCount)
                .mapToObj(i -> login().refreshToken())
                .toList();

        // when
        List<TokenResult> sut = runConcurrently(sessionCount,
                i -> tokenReissueUseCase.reissue(new TokenReissueCommand(refreshTokens.get(i))));

        // then
        // 세션마다 다른 행을 잠그므로 서로 기다리지 않고, 각 행이 제자리에서 회전한다
        assertThat(sut).hasSize(sessionCount).doesNotContainNull();
        List<RefreshToken> sessions = sessionsOf(member);
        assertSoftly(softly -> {
            softly.assertThat(sessions).hasSize(sessionCount);
            softly.assertThat(sessions).extracting(RefreshToken::getTokenHash)
                    .containsExactlyInAnyOrderElementsOf(sut.stream()
                            .map(result -> TokenHasher.sha256Hex(result.refreshToken())).toList());
        });
    }

    @Test
    void 정리와_재발급이_동시에_돌아도_유효한_세션은_지워지지_않고_재발급된다() throws Exception {
        // given
        Member member = saveMember();
        int sessionCount = 4;
        List<String> refreshTokens = IntStream.range(0, sessionCount)
                .mapToObj(i -> login().refreshToken())
                .toList();
        // 다른 회원의 버려진 세션. 묶음 크기보다 많이 두어 정리가 여러 트랜잭션에 걸쳐 재발급과 겹치게 한다
        LocalDateTime now = LocalDateTime.now(clock);
        int expiredCount = 50;
        IntStream.range(0, expiredCount).forEach(i -> refreshTokenRepository.save(
                RefreshToken.create(-1L, TokenHasher.sha256Hex("abandoned-" + i), now.minusDays(1).minusMinutes(i))));
        refreshTokenRepository.flush();

        // when
        // 마지막 작업은 정리, 나머지는 세션마다 재발급
        List<Object> sut = runConcurrently(sessionCount + 1, i -> i == sessionCount
                ? refreshTokenCleanup.deleteExpired(5)
                : tokenReissueUseCase.reissue(new TokenReissueCommand(refreshTokens.get(i))));

        // then
        assertThat(sut).hasSize(sessionCount + 1).doesNotContainNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.get(sessionCount)).isEqualTo(expiredCount);
            softly.assertThat(sessionsOf(member)).hasSize(sessionCount);
            softly.assertThat(refreshTokenRepository.findAll()).hasSize(sessionCount);
        });
    }

    @Test
    void 로그아웃이_먼저_행을_지우면_같은_토큰의_재발급은_기다렸다가_실패한다() throws Exception {
        // given
        Member member = saveMember();
        String refreshToken = login().refreshToken();
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        Future<TokenResult> reissue;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                // when
                // 로그아웃이 행을 지운 채 커밋을 미뤄, 재발급이 그 행의 잠금을 기다리게 한다
                Future<?> logout = executor.submit(() -> inTransaction(() -> {
                    logoutUseCase.logout(new LogoutCommand(refreshToken));
                    deleted.countDown();
                    await(commit);
                    return null;
                }));
                await(deleted);
                reissue = executor.submit(() -> tokenReissueUseCase.reissue(new TokenReissueCommand(refreshToken)));
                // 재발급이 이 시간 안에 대기에 들어가지 못하면 로그아웃 커밋 뒤에 시작해 같은 결과(실패)가 나므로,
                // 대기 경로를 거쳤는지는 보장하지 못한다. 교착 없이 끝나고 세션이 살아나지 않는 것을 확인한다
                Thread.sleep(LOCK_WAIT_PAUSE_MILLIS);
                commit.countDown();
                logout.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                commit.countDown();
            }

            // then
            // 재발급의 잠금 조회는 커밋된 최신 상태를 읽어 행이 없으므로 실패한다
            assertThatThrownBy(() -> reissue.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(RestApiException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
        }
        assertThat(sessionsOf(member)).isEmpty();
    }

    @Test
    void 재발급이_행을_잠근_사이_로그아웃이_행을_찾았으면_재발급이_커밋된_뒤에도_세션이_지워진다() throws Exception {
        // given
        Member member = saveMember();
        String refreshToken = login().refreshToken();
        CountDownLatch rotated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        TokenResult reissued;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                // when
                // 재발급이 해시를 바꾼 채 커밋을 미룬다. 로그아웃은 커밋 전 상태를 읽어 옛 해시로 id를 찾고, DELETE에서 잠금을 기다린다
                Future<TokenResult> reissue = executor.submit(() -> inTransaction(() -> {
                    TokenResult result = tokenReissueUseCase.reissue(new TokenReissueCommand(refreshToken));
                    rotated.countDown();
                    await(commit);
                    return result;
                }));
                await(rotated);
                Future<?> logout = executor.submit(() -> logoutUseCase.logout(new LogoutCommand(refreshToken)));
                // 로그아웃이 재발급 커밋 전에 id를 찾아야 하는 순서다. 늦으면 행이 남아 아래 검증에서 실패한다
                Thread.sleep(LOCK_WAIT_PAUSE_MILLIS);
                commit.countDown();
                reissued = reissue.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                logout.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                commit.countDown();
            }
        }

        // then
        // 해시가 바뀌었어도 같은 id의 행을 지워, 재발급으로 받은 새 토큰의 세션도 남지 않는다
        assertThat(sessionsOf(member)).isEmpty();
        assertThatThrownBy(() -> tokenReissueUseCase.reissue(new TokenReissueCommand(reissued.refreshToken())))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
    }

    private Member saveMember() {
        return memberRepository.saveAndFlush(Member.createUser(LOGIN_ID, passwordEncoder.encode(PASSWORD),
                "session01@example.com", "홍길동", "길동이", LocalDateTime.of(2026, 10, 1, 12, 0)));
    }

    private TokenResult login() {
        return loginUseCase.login(new LoginCommand(LOGIN_ID, PASSWORD));
    }

    // 공유 DB에 다른 테스트의 행이 있어도 흔들리지 않게 이 회원의 행만 센다
    private List<RefreshToken> sessionsOf(Member member) {
        return refreshTokenRepository.findAll().stream()
                .filter(token -> token.getMemberId().equals(member.getId()))
                .toList();
    }

    // 모든 작업을 동시에 출발시키고, 제한 시간 안에 끝나지 않으면(교착·잠금 대기) 바로 실패시킨다
    private <T> List<T> runConcurrently(int count, Task<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> callables = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            callables.add(() -> {
                ready.countDown();
                start.await();
                return task.run(index);
            });
        }
        List<T> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = callables.stream().map(executor::submit).toList();
            ready.await();
            start.countDown();
            try {
                for (Future<T> future : futures) {
                    results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                }
            } finally {
                // close()는 남은 작업이 끝날 때까지 기다리므로, 실패했으면 멈춘 작업을 먼저 중단한다
                executor.shutdownNow();
            }
        }
        return results;
    }

    // 유스케이스를 바깥 트랜잭션 안에서 실행해, 작업이 끝난 뒤 커밋 시점을 테스트가 정할 수 있게 한다
    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("제한 시간 안에 신호가 오지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface Task<T> {
        T run(int index);
    }

}
