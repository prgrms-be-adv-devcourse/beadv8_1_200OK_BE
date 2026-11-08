package com.ok.member.auth;

import com.ok.member.auth.repository.RefreshTokenRepository;
import com.ok.member.member.adapter.out.persistence.MemberRepository;
import com.ok.member.member.domain.Member;
import com.ok.testsupport.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 커넥션 풀 크기보다 많은 로그인이 동시에 와도 서로를 기다리며 멈추지 않는지 검증한다(#425).
 * 요청 하나가 커넥션을 쥔 채 하나를 더 요구하면, 풀이 가득 찬 순간 모든 요청이 서로를 기다려 connectionTimeout까지 멈춘다.
 * 풀 크기를 테스트용으로 바꾸면 스프링 컨텍스트가 새로 뜨므로, 공유 컨텍스트의 실제 풀 크기를 읽어 그보다 많이 보낸다.
 */
class AuthLoginConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "password1!";

    // 데드락이 나면 connectionTimeout(기본 30초)까지 멈추므로, 정상 처리 시간보다 넉넉하고 그보다는 짧게 잡는다
    private static final long TIMEOUT_SECONDS = 15;

    @Autowired
    WebApplicationContext context;

    @Autowired
    DataSource dataSource;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JsonMapper jsonMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 커넥션_풀_크기보다_많은_로그인이_동시에_와도_모두_제한_시간_안에_성공한다() throws Exception {
        // given
        int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        int requestCount = poolSize * 2;
        List<Member> members = saveMembers(requestCount);
        Set<Long> memberIds = members.stream().map(Member::getId).collect(Collectors.toSet());

        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (Member member : members) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return performLogin(member.getLoginId());
            });
        }

        // when
        List<Integer> sut = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(requestCount)) {
            List<Future<Integer>> futures = tasks.stream().map(executor::submit).toList();
            ready.await();
            start.countDown();
            try {
                for (Future<Integer> future : futures) {
                    sut.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                }
            } catch (TimeoutException e) {
                // close()는 남은 작업이 끝날 때까지 기다리므로, 멈춘 요청을 먼저 중단해 실패를 바로 알린다
                executor.shutdownNow();
                throw e;
            }
        }

        // then: 응답만 200이고 저장이 빠지는 경우도 잡도록 Refresh Token이 회원마다 저장됐는지 함께 본다.
        // 공유 DB에 다른 테스트의 행이 있어도 흔들리지 않게 이 테스트가 만든 회원의 토큰만 센다
        assertThat(sut).hasSize(requestCount).containsOnly(200);
        long issuedTokenCount = refreshTokenRepository.findAll().stream()
                .filter(token -> memberIds.contains(token.getMemberId()))
                .count();
        assertThat(issuedTokenCount).isEqualTo(requestCount);
    }

    private List<Member> saveMembers(int count) {
        // bcrypt 인코딩은 비싸므로 한 번만 하고 모든 회원이 같은 해시를 쓴다
        String encodedPassword = passwordEncoder.encode(PASSWORD);
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String loginId = "tester" + i;
            members.add(memberRepository.save(Member.createUser(loginId, encodedPassword, loginId + "@example.com",
                    "홍길동", "길동이" + i, LocalDateTime.of(2026, 10, 1, 12, 0))));
        }
        memberRepository.flush();
        return members;
    }

    private int performLogin(String loginId) throws Exception {
        String body = jsonMapper.writeValueAsString(Map.of("loginId", loginId, "password", PASSWORD));
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getStatus();
    }

}
