package com.ok.member.auth;

import com.ok.PaldoGotganApplication;
import org.springframework.boot.test.context.SpringBootTest;
import com.jayway.jsonpath.JsonPath;
import com.ok.common.security.TokenClaims;
import com.ok.member.auth.repository.RefreshTokenRepository;
import com.ok.member.member.domain.Member;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.seller.domain.SellerApplication;
import com.ok.member.seller.domain.SellerApplicationStatus;
import com.ok.member.seller.repository.SellerApplicationRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 로그인·재발급 API로 토큰을 받아, 판매자 승인 상태가 토큰 권한과 판매자 API 인가에 반영되는지 확인한다.
@SpringBootTest(classes = PaldoGotganApplication.class)
class AuthSellerRoleIntegrationTest extends AbstractIntegrationTest {

    private static final String LOGIN_ID = "seller01";
    private static final String PASSWORD = "password1!";
    private static final LocalDateTime APPLIED_AT = LocalDateTime.of(2026, 10, 18, 14, 0);
    private static final LocalDateTime REVIEW_STARTED_AT = LocalDateTime.of(2026, 10, 19, 9, 0);
    private static final LocalDateTime DECIDED_AT = LocalDateTime.of(2026, 10, 20, 11, 0);

    @Autowired
    WebApplicationContext context;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    SellerApplicationRepository sellerApplicationRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    JsonMapper jsonMapper;

    private MockMvc mockMvc;

    private Long memberId;

    private Long reviewerId;

    @BeforeEach
    void setUp() {
        // 판매자 상품 API의 인가(@PreAuthorize)까지 확인하려면 보안 필터를 적용해야 한다
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        memberId = memberRepository.saveAndFlush(Member.createUser(LOGIN_ID, passwordEncoder.encode(PASSWORD),
                "seller01@example.com", "김영수", "seller01", LocalDateTime.of(2026, 10, 1, 0, 0))).getId();
        // reviewer_id는 member를 외래키로 참조한다. 관리자 회원 생성 메서드가 아직 없어, 심사자를 일반 회원으로 만든다
        reviewerId = memberRepository.saveAndFlush(Member.createUser("admin01", "{bcrypt}encoded",
                "admin01@example.com", "관리자", "admin01", LocalDateTime.of(2026, 10, 1, 0, 0))).getId();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 승인된_판매자가_로그인하면_구매자와_판매자_권한을_받는다() throws Exception {
        // given
        saveApplicationIn(SellerApplicationStatus.APPROVED);

        // when
        String body = login();

        // then
        assertThat(roles(body)).containsExactly("BUYER", "SELLER");
    }

    @ParameterizedTest
    @EnumSource(value = SellerApplicationStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
    void 승인되지_않은_신청만_있으면_구매자_권한만_받는다(SellerApplicationStatus status) throws Exception {
        // given
        saveApplicationIn(status);

        // when
        String body = login();

        // then
        assertThat(roles(body)).containsExactly("BUYER");
    }

    @Test
    void 승인_전에_로그인한_회원이_승인_뒤_토큰을_재발급받으면_판매자_권한이_붙는다() throws Exception {
        // given: 심사 중에 로그인해 구매자 권한만 가진 토큰을 받았다
        SellerApplication application = saveApplicationIn(SellerApplicationStatus.IN_REVIEW);
        String loginBody = login();
        assertThat(roles(loginBody)).containsExactly("BUYER");

        // 그 뒤 관리자가 승인했다
        application.approve(reviewerId, DECIDED_AT);
        sellerApplicationRepository.saveAndFlush(application);

        // when
        String reissueBody = mockMvc.perform(post("/api/v1/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(
                                Map.of("refreshToken", JsonPath.read(loginBody, "$.refreshToken")))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // then
        assertThat(roles(reissueBody)).containsExactly("BUYER", "SELLER");
    }

    @Test
    void 승인된_판매자의_토큰은_판매자_상품_API의_인가를_통과한다() throws Exception {
        // given
        saveApplicationIn(SellerApplicationStatus.APPROVED);
        String accessToken = JsonPath.read(login(), "$.accessToken");

        // when: 없는 상품을 지우면, 인가를 통과했을 때만 상품을 찾다가 404가 난다
        ResultActions result = mockMvc.perform(delete("/api/v1/products/{productId}", 999_999L)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));

        // then
        result.andExpect(status().isNotFound());
    }

    @Test
    void 승인되지_않은_회원의_토큰은_판매자_상품_API에서_403을_받는다() throws Exception {
        // given
        saveApplicationIn(SellerApplicationStatus.PENDING);
        String accessToken = JsonPath.read(login(), "$.accessToken");

        // when
        ResultActions result = mockMvc.perform(delete("/api/v1/products/{productId}", 999_999L)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));

        // then
        result.andExpect(status().isForbidden());
    }

    private String login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("loginId", LOGIN_ID, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private List<String> roles(String tokenResponseBody) {
        String accessToken = JsonPath.read(tokenResponseBody, "$.accessToken");
        return jwtDecoder.decode(accessToken).getClaimAsStringList(TokenClaims.ROLES);
    }

    // 상태를 하나 추가하면 default가 없는 이 switch가 컴파일되지 않아, 권한 테스트에 새 상태를 넣도록 강제한다
    private SellerApplication saveApplicationIn(SellerApplicationStatus status) {
        SellerApplication application = SellerApplication.apply(memberId, "청송 햇살농원", "김영수",
                "512-81-04273", "제2026-경북청송-0031호", APPLIED_AT);
        switch (status) {
            case PENDING -> {
                // 신청하면 심사 대기 상태라 바꿀 것이 없다
            }
            case IN_REVIEW -> application.startReview(REVIEW_STARTED_AT);
            case APPROVED -> {
                application.startReview(REVIEW_STARTED_AT);
                application.approve(reviewerId, DECIDED_AT);
            }
            case REJECTED -> application.reject(reviewerId, "통신판매업 신고번호를 확인할 수 없습니다.", DECIDED_AT);
        }
        return sellerApplicationRepository.saveAndFlush(application);
    }

    // 판매자 신청과 Refresh Token이 member를 외래키로 참조하므로 먼저 지운다
    private void cleanUp() {
        refreshTokenRepository.deleteAllInBatch();
        sellerApplicationRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

}
