package com.ok.member.seller;

import com.ok.member.member.domain.Member;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.seller.domain.SellerProfile;
import com.ok.member.seller.repository.SellerProfileRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 판매자 프로필 API를 호출해, 승인 여부 확인부터 사진 key 저장·URL 응답까지 실제와 같게 확인한다.
class SellerProfileIntegrationTest extends AbstractIntegrationTest {

    private static final String PROFILE_URL = "/api/v1/sellers/me/profile";
    // application-test.yml의 app.s3.bucket, app.s3.region으로 만든 주소 (MediaUrlResolver와 같은 형식)
    private static final String BASE_URL = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/";
    private static final String IMAGE_KEY = "seller_profile/3f2a9c1e-7b4d-4e8a-9f0c-1d2e3f4a5b6c.jpg";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 18, 14, 30);

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    SellerProfileRepository sellerProfileRepository;

    private MockMvc mockMvc;

    private Long memberId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        memberId = memberRepository.saveAndFlush(Member.createUser("seller01", "{bcrypt}encoded",
                "seller01@example.com", "김영수", "seller01", AT)).getId();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 판매자가_프로필을_등록하면_201과_사진_URL을_반환하고_사진은_key로_저장한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(withToken(post(PROFILE_URL))
                .content(profileBody("청송 햇살농원", BASE_URL + IMAGE_KEY)));

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeName").value("청송 햇살농원"))
                .andExpect(jsonPath("$.representativeName").value("김영수"))
                .andExpect(jsonPath("$.customerServicePhone").value("054-873-2210"))
                .andExpect(jsonPath("$.introduction").value("3대째 사과만 키우는 청송 부남면 과수원입니다."))
                .andExpect(jsonPath("$.profileImageUrl").value(BASE_URL + IMAGE_KEY));

        SellerProfile sut = sellerProfileRepository.findByMemberId(memberId).orElse(null);
        assertThat(sut).isNotNull();
        assertThat(sut.getProfileImageKey()).isEqualTo(IMAGE_KEY);
    }

    @Test
    void SELLER_권한이_없는_토큰은_조회_등록_수정이_모두_403이고_프로필을_만들지_않는다() throws Exception {
        // given: 판매자 승인 전이거나, 승인 직후 재발급받기 전의 구매자 토큰
        String buyerToken = tokens.bearer(memberId, "BUYER");

        // when
        ResultActions getResult = mockMvc.perform(get(PROFILE_URL).header(HttpHeaders.AUTHORIZATION, buyerToken));
        ResultActions registerResult = mockMvc.perform(post(PROFILE_URL)
                .header(HttpHeaders.AUTHORIZATION, buyerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileBody("청송 햇살농원", null)));
        ResultActions updateResult = mockMvc.perform(put(PROFILE_URL)
                .header(HttpHeaders.AUTHORIZATION, buyerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(profileBody("청송 햇살농원", null)));

        // then
        getResult.andExpect(status().isForbidden()).andExpect(jsonPath("$.type", endsWith("/forbidden")));
        registerResult.andExpect(status().isForbidden());
        updateResult.andExpect(status().isForbidden());
        assertThat(sellerProfileRepository.existsByMemberId(memberId)).isFalse();
    }

    @Test
    void 토큰_없이_호출하면_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get(PROFILE_URL));

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    void 이미_프로필을_등록했으면_409와_중복_등록_type을_반환한다() throws Exception {
        // given
        mockMvc.perform(withToken(post(PROFILE_URL)).content(profileBody("청송 햇살농원", null)))
                .andExpect(status().isCreated());

        // when
        ResultActions result = mockMvc.perform(withToken(post(PROFILE_URL))
                .content(profileBody("청송 햇살농원", null)));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/member/seller-profile-already-exists")));
    }

    @Test
    void 프로필을_수정하면_전체_값을_바꾸고_사진_URL을_비우면_사진을_지운다() throws Exception {
        // given
        sellerProfileRepository.saveAndFlush(SellerProfile.register(
                memberId, "청송 햇살농원", "김영수", "054-873-2210", "소개", IMAGE_KEY));

        // when
        ResultActions result = mockMvc.perform(withToken(put(PROFILE_URL))
                .content(profileBody("청송 사과농원", null)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("청송 사과농원"))
                .andExpect(jsonPath("$.profileImageUrl").value(nullValue()));

        SellerProfile sut = sellerProfileRepository.findByMemberId(memberId).orElse(null);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStoreName()).isEqualTo("청송 사과농원");
            softly.assertThat(sut.getIntroduction()).isEqualTo("3대째 사과만 키우는 청송 부남면 과수원입니다.");
            softly.assertThat(sut.getProfileImageKey()).isNull();
        });
    }

    @Test
    void 프로필을_등록하지_않고_수정하면_404를_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(withToken(put(PROFILE_URL))
                .content(profileBody("청송 햇살농원", null)));

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/member/seller-profile-not-found")));
    }

    @Test
    void 판매자_프로필용이_아닌_사진_URL이면_422를_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(withToken(post(PROFILE_URL))
                .content(profileBody("청송 햇살농원", BASE_URL + "product/3f2a9c1e.jpg")));

        // then
        result.andExpect(status().is(422))
                .andExpect(jsonPath("$.type", endsWith("/member/invalid-profile-image-url")));
    }

    @Test
    void 등록한_프로필을_조회한다() throws Exception {
        // given
        sellerProfileRepository.saveAndFlush(SellerProfile.register(
                memberId, "청송 햇살농원", "김영수", "054-873-2210", null, null));

        // when
        ResultActions result = mockMvc.perform(withToken(get(PROFILE_URL)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("청송 햇살농원"))
                .andExpect(jsonPath("$.introduction").value(nullValue()))
                .andExpect(jsonPath("$.profileImageUrl").value(nullValue()));
    }

    @Test
    void 프로필을_등록하지_않았으면_조회할_때_404를_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(withToken(get(PROFILE_URL)));

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/member/seller-profile-not-found")));
    }

    // 판매자 승인을 받은 회원이 로그인하면 받는 토큰 (#212). 판매자 여부는 토큰 권한으로만 판단한다
    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "BUYER", "SELLER"))
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String profileBody(String storeName, String profileImageUrl) {
        // 사진 URL이 null인 경우를 보내야 해서 null을 허용하는 Map을 쓴다
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storeName", storeName);
        body.put("representativeName", "김영수");
        body.put("customerServicePhone", "054-873-2210");
        body.put("introduction", "3대째 사과만 키우는 청송 부남면 과수원입니다.");
        body.put("profileImageUrl", profileImageUrl);
        return jsonMapper.writeValueAsString(body);
    }

    // 판매자 프로필이 member를 외래키로 참조하므로 먼저 지운다
    private void cleanUp() {
        sellerProfileRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

}
