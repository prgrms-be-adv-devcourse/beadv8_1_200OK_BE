package com.ok.member.member;

import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberStatus;
import com.ok.member.member.repository.MemberRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 내 정보 API를 호출해, 토큰의 회원 id로 본인 정보만 조회·수정하는지 실제와 같게 확인한다
class MemberInfoIntegrationTest extends AbstractIntegrationTest {

    private static final String ME_URL = "/api/v1/members/me";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 19, 12, 0);

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    MemberRepository memberRepository;

    private MockMvc mockMvc;

    private Long memberId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        memberRepository.deleteAllInBatch();
        memberId = memberRepository.saveAndFlush(createUser("seoyeon88", "seoyeon88@example.com", "주말장보기"))
                .getId();
    }

    @AfterEach
    void tearDown() {
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 내_정보를_조회하면_아이디_이메일_이름_닉네임을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(withToken(get(ME_URL)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("seoyeon88"))
                .andExpect(jsonPath("$.email").value("seoyeon88@example.com"))
                .andExpect(jsonPath("$.name").value("이서연"))
                .andExpect(jsonPath("$.nickname").value("주말장보기"));
    }

    @Test
    void 이름과_닉네임을_수정하면_200과_바뀐_정보를_반환하고_아이디와_이메일은_그대로다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(withToken(put(ME_URL))
                .content(updateBody(" 이서현 ", " 토요일장보기 ")));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("seoyeon88"))
                .andExpect(jsonPath("$.email").value("seoyeon88@example.com"))
                .andExpect(jsonPath("$.name").value("이서현"))
                .andExpect(jsonPath("$.nickname").value("토요일장보기"));

        Member sut = memberRepository.findById(memberId).orElse(null);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getName()).isEqualTo("이서현");
            softly.assertThat(sut.getNickname()).isEqualTo("토요일장보기");
            softly.assertThat(sut.getLoginId()).isEqualTo("seoyeon88");
            softly.assertThat(sut.getEmail()).isEqualTo("seoyeon88@example.com");
        });
    }

    @Test
    void 다른_회원과_같은_닉네임으로_수정할_수_있다() throws Exception {
        // given
        memberRepository.saveAndFlush(createUser("minsu01", "minsu01@example.com", "농부의아들"));

        // when
        ResultActions result = mockMvc.perform(withToken(put(ME_URL))
                .content(updateBody("이서연", "농부의아들")));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("농부의아들"));
    }

    @Test
    void 공백을_뺀_닉네임이_2자_미만이면_422를_반환하고_수정하지_않는다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(withToken(put(ME_URL))
                .content(updateBody("이서연", " 주 ")));

        // then
        result.andExpect(status().is(422))
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));

        Member sut = memberRepository.findById(memberId).orElse(null);
        assertThat(sut).isNotNull();
        assertThat(sut.getNickname()).isEqualTo("주말장보기");
    }

    @Test
    void 토큰_없이_호출하면_조회와_수정_모두_401을_반환한다() throws Exception {
        // when
        ResultActions getResult = mockMvc.perform(get(ME_URL));
        ResultActions updateResult = mockMvc.perform(put(ME_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody("이서연", "주말장보기")));

        // then
        getResult.andExpect(status().isUnauthorized());
        updateResult.andExpect(status().isUnauthorized());
    }

    @Test
    void 탈퇴한_회원의_토큰이면_404를_반환한다() throws Exception {
        // given: 탈퇴 후 만료 전 Access Token이 남아 있는 경우
        Member withdrawn = createUser("withdrawn01", "withdrawn01@example.com", "떠난회원");
        ReflectionTestUtils.setField(withdrawn, "status", MemberStatus.WITHDRAWN);
        Long withdrawnId = memberRepository.saveAndFlush(withdrawn).getId();

        // when
        ResultActions result = mockMvc.perform(get(ME_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(withdrawnId, "BUYER")));

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/member/member-not-found")));
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String updateBody(String name, String nickname) {
        return jsonMapper.writeValueAsString(Map.of("name", name, "nickname", nickname));
    }

    private Member createUser(String loginId, String email, String nickname) {
        return Member.createUser(loginId, "{bcrypt}encoded", email, "이서연", nickname, AT);
    }

}
