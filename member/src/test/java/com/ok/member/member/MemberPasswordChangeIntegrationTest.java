package com.ok.member.member;

import com.ok.member.member.adapter.out.persistence.MemberJpaRepository;
import com.ok.member.member.domain.Member;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 bcrypt와 DB 잠금으로 비밀번호 변경 API를 호출해, 토큰 버전이 오르는 조건과 동시 변경을 확인한다
class MemberPasswordChangeIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/members/me/password";
    private static final String PASSWORD = "password1!";
    private static final String NEW_PASSWORD = "newPassword1!";

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    MemberJpaRepository memberRepository;

    private MockMvc mockMvc;

    private Long memberId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        memberRepository.deleteAllInBatch();
        memberId = memberRepository.saveAndFlush(Member.createUser("seoyeon88", passwordEncoder.encode(PASSWORD),
                "seoyeon88@example.com", "이서연", "주말장보기", LocalDateTime.of(2026, 10, 19, 12, 0))).getId();
    }

    @AfterEach
    void tearDown() {
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 현재_비밀번호가_맞으면_204를_반환하고_새_비밀번호로_바꾸고_토큰_버전을_올린다() throws Exception {
        // when
        ResultActions result = changePassword(PASSWORD, NEW_PASSWORD);

        // then
        result.andExpect(status().isNoContent());
        Member sut = memberRepository.findById(memberId).orElse(null);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(passwordEncoder.matches(NEW_PASSWORD, sut.getPassword())).isTrue();
            softly.assertThat(sut.getTokenVersion()).isEqualTo(1L);
            softly.assertThat(sut.getTokenVersionChangedAt()).isNotNull();
        });
    }

    @Test
    void 현재_비밀번호가_틀리면_400을_반환하고_비밀번호와_토큰_버전을_그대로_둔다() throws Exception {
        // when
        ResultActions result = changePassword("wrongPassword1!", NEW_PASSWORD);

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type", endsWith("/member/invalid-current-password")));
        assertUnchanged();
    }

    @ParameterizedTest
    @MethodSource("규칙에_맞지_않는_새_비밀번호")
    void 새_비밀번호가_가입_규칙에_맞지_않으면_422를_반환하고_바꾸지_않는다(String newPassword) throws Exception {
        // when
        ResultActions result = changePassword(PASSWORD, newPassword);

        // then
        result.andExpect(status().is(422))
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
        assertUnchanged();
    }

    static Stream<String> 규칙에_맞지_않는_새_비밀번호() {
        return Stream.of("", "   ", "short1!", "onlyletters!", "password123", "a1!".repeat(7));
    }

    @Test
    void 새_비밀번호가_현재와_같으면_400을_반환하고_토큰_버전을_올리지_않는다() throws Exception {
        // when
        ResultActions result = changePassword(PASSWORD, PASSWORD);

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type", endsWith("/member/same-as-current-password")));
        assertUnchanged();
    }

    @Test
    void 현재_비밀번호가_20자를_넘으면_bcrypt까지_가지_않고_422를_반환한다() throws Exception {
        // when
        ResultActions result = changePassword("a1!".repeat(100), NEW_PASSWORD);

        // then
        result.andExpect(status().is(422))
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
        assertUnchanged();
    }

    @Test
    void 토큰_없이_호출하면_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(patch(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(PASSWORD, NEW_PASSWORD)));

        // then
        result.andExpect(status().isUnauthorized());
        assertUnchanged();
    }

    @Test
    void 같은_회원의_비밀번호_변경이_동시에_오면_하나만_성공하고_토큰_버전은_1만_오른다() throws Exception {
        // given: 두 요청 모두 같은 현재 비밀번호로 bcrypt 확인을 통과한 뒤 회원 행을 두고 경쟁한다
        int requestCount = 2;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            String newPassword = NEW_PASSWORD + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return changePassword(PASSWORD, newPassword).andReturn().getResponse().getStatus();
            });
        }

        // when
        List<Integer> sut = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(requestCount)) {
            List<Future<Integer>> futures = tasks.stream().map(executor::submit).toList();
            ready.await();
            start.countDown();
            for (Future<Integer> future : futures) {
                sut.add(future.get(15, TimeUnit.SECONDS));
            }
        }

        // then: 진 요청은 보통 잠근 뒤 해시가 바뀐 것을 보고 409를 받는다. 이긴 요청이 진 요청의 bcrypt 확인보다 먼저
        // 커밋까지 끝나면 진 요청은 현재 비밀번호 불일치(400)를 받는다. 어느 쪽이든 성공은 하나뿐이어야 한다
        assertThat(sut).hasSize(requestCount).containsOnlyOnce(204);
        assertThat(sut).filteredOn(statusCode -> statusCode != 204).allMatch(statusCode -> statusCode == 409 || statusCode == 400);
        Member member = memberRepository.findById(memberId).orElse(null);
        assertThat(member).isNotNull();
        assertThat(member.getTokenVersion()).isEqualTo(1L);
    }

    private void assertUnchanged() {
        Member sut = memberRepository.findById(memberId).orElse(null);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(passwordEncoder.matches(PASSWORD, sut.getPassword())).isTrue();
            softly.assertThat(sut.getTokenVersion()).isZero();
            softly.assertThat(sut.getTokenVersionChangedAt()).isNull();
        });
    }

    private ResultActions changePassword(String currentPassword, String newPassword) throws Exception {
        return mockMvc.perform(patch(URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(currentPassword, newPassword)));
    }

    private String body(String currentPassword, String newPassword) {
        return jsonMapper.writeValueAsString(Map.of("currentPassword", currentPassword, "newPassword", newPassword));
    }

}
