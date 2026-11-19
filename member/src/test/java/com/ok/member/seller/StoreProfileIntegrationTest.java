package com.ok.member.seller;

import com.ok.member.member.adapter.out.persistence.MemberJpaRepository;
import com.ok.member.member.adapter.out.persistence.MemberOutboxJpaRepository;
import com.ok.member.member.domain.Member;
import com.ok.member.seller.adapter.out.persistence.SellerProfileJpaRepository;
import com.ok.member.seller.domain.SellerProfile;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 상품 상세 아래 보이는 상점 프로필을 로그인 여부·역할과 상관없이 조회하는지, 판매자 본인 API는 그대로 막히는지 확인한다 (#740)
class StoreProfileIntegrationTest extends AbstractIntegrationTest {

    private static final String STORE_URL = "/api/v1/stores/{sellerId}";
    // application-test.yml의 app.s3.bucket, app.s3.region으로 만든 주소 (MediaUrlResolver와 같은 형식)
    private static final String BASE_URL = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/";
    private static final String IMAGE_KEY = "seller_profile/3f2a9c1e-7b4d-4e8a-9f0c-1d2e3f4a5b6c.jpg";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 18, 14, 30);
    // 상점 주인이 아닌 다른 회원. 토큰의 회원 id는 이 API의 응답에 영향이 없어야 한다
    private static final Long VIEWER_ID = 999L;

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    MemberJpaRepository memberRepository;

    @Autowired
    SellerProfileJpaRepository sellerProfileRepository;

    @Autowired
    MemberOutboxJpaRepository memberOutboxRepository;

    private MockMvc mockMvc;

    private Long sellerId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        sellerId = memberRepository.saveAndFlush(Member.createUser("seller01", "{bcrypt}encoded",
                "seller01@example.com", "김영수", "seller01", AT)).getId();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 토큰_없이_조회하면_출고지_우편번호를_뺀_상점_프로필을_반환한다() throws Exception {
        // given
        sellerProfileRepository.saveAndFlush(SellerProfile.register(sellerId, "청송 햇살농원", "김영수",
                "054-873-2210", "37421", "3대째 사과만 키우는 청송 부남면 과수원입니다.", IMAGE_KEY));

        // when
        ResultActions result = mockMvc.perform(get(STORE_URL, sellerId));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(sellerId))
                .andExpect(jsonPath("$.storeName").value("청송 햇살농원"))
                .andExpect(jsonPath("$.representativeName").value("김영수"))
                .andExpect(jsonPath("$.customerServicePhone").value("054-873-2210"))
                .andExpect(jsonPath("$.introduction").value("3대째 사과만 키우는 청송 부남면 과수원입니다."))
                .andExpect(jsonPath("$.profileImageUrl").value(BASE_URL + IMAGE_KEY))
                .andExpect(jsonPath("$.originPostalCode").doesNotExist());
    }

    @ParameterizedTest
    @MethodSource("조회하는_회원의_역할")
    void 구매자_판매자_관리자_모두_상점_프로필을_조회할_수_있다(String[] roles) throws Exception {
        // given
        sellerProfileRepository.saveAndFlush(SellerProfile.register(sellerId, "청송 햇살농원", "김영수",
                "054-873-2210", "37421", null, null));

        // when
        ResultActions result = mockMvc.perform(get(STORE_URL, sellerId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(VIEWER_ID, roles)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(sellerId))
                .andExpect(jsonPath("$.storeName").value("청송 햇살농원"));
    }

    static Stream<Arguments> 조회하는_회원의_역할() {
        return Stream.of(
                Arguments.of((Object) new String[]{"BUYER"}),
                Arguments.of((Object) new String[]{"BUYER", "SELLER"}),
                Arguments.of((Object) new String[]{"ADMIN"})
        );
    }

    @Test
    void 만료된_토큰이_붙어도_토큰을_읽지_않고_상점_프로필을_반환한다() throws Exception {
        // given: 로그인이 풀린 채 상품 상세를 보는 사용자
        sellerProfileRepository.saveAndFlush(SellerProfile.register(sellerId, "청송 햇살농원", "김영수",
                "054-873-2210", "37421", null, null));

        // when
        ResultActions result = mockMvc.perform(get(STORE_URL, sellerId)
                .header(HttpHeaders.AUTHORIZATION, tokens.expiredBearer(VIEWER_ID, "BUYER")));

        // then
        result.andExpect(status().isOk());
    }

    @Test
    void 프로필을_등록하지_않은_판매자면_404를_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get(STORE_URL, sellerId));

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/member/seller-profile-not-found")));
    }

    @Test
    void 상점_프로필을_공개해도_판매자_본인_프로필_API는_토큰_없이_401이다() throws Exception {
        // when: 공개 경로를 /sellers/{id}/profile로 두면 /sellers/me/profile까지 열린다. 그렇지 않은지 지킨다
        ResultActions result = mockMvc.perform(get("/api/v1/sellers/me/profile"));

        // then
        result.andExpect(status().isUnauthorized());
    }

    // 판매자 프로필과 출고지 알림용 outbox 줄(#587)이 member를 외래키로 참조하므로 먼저 지운다
    private void cleanUp() {
        memberOutboxRepository.deleteAllInBatch();
        sellerProfileRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

}
