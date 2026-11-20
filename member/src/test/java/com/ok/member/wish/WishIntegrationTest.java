package com.ok.member.wish;

import com.ok.member.member.adapter.out.persistence.MemberJpaRepository;
import com.ok.member.member.domain.Member;
import com.ok.member.wish.adapter.out.persistence.MemberProductWishJpaRepository;
import com.ok.member.wish.domain.MemberProductWish;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 찜 API를 호출해, 등록·해제·되살리기가 행에 어떻게 남는지와 목록 순서를 실제 DB로 확인한다.
class WishIntegrationTest extends AbstractIntegrationTest {

    private static final String WISHES_URL = "/api/v1/members/me/wishes";
    // 직접 넣는 행의 찜한 시각. API가 쓰는 현재 시각보다 항상 앞서도록 과거로 둔다
    private static final LocalDateTime PAST = LocalDateTime.of(2026, 1, 1, 9, 0);

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    MemberJpaRepository memberRepository;

    @Autowired
    MemberProductWishJpaRepository wishRepository;

    private MockMvc mockMvc;

    private Long memberId;

    private Long otherMemberId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        memberId = memberRepository.saveAndFlush(Member.createUser("buyer01", "{bcrypt}encoded",
                "buyer01@example.com", "김민지", "buyer01", PAST)).getId();
        otherMemberId = memberRepository.saveAndFlush(Member.createUser("buyer02", "{bcrypt}encoded",
                "buyer02@example.com", "이서준", "buyer02", PAST)).getId();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 찜하면_204를_받고_행이_생긴다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(asBuyer(put(WISHES_URL + "/101")));

        // then
        result.andExpect(status().isNoContent());
        MemberProductWish sut = wishRepository.findByMemberIdAndProductId(memberId, 101L).orElse(null);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.isWished()).isTrue();
            softly.assertThat(sut.getWishedAt()).isNotNull();
        });
    }

    @Test
    void 같은_상품을_다시_찜해도_행_개수와_찜한_시각이_바뀌지_않는다() throws Exception {
        // given
        wishRepository.saveAndFlush(MemberProductWish.wish(memberId, 101L, PAST));

        // when
        ResultActions result = mockMvc.perform(asBuyer(put(WISHES_URL + "/101")));

        // then
        result.andExpect(status().isNoContent());
        List<MemberProductWish> rows = wishRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getWishedAt()).isEqualTo(PAST);
    }

    @Test
    void 해제하면_204를_받고_행은_남은_채_해제_시각이_채워진다() throws Exception {
        // given
        wishRepository.saveAndFlush(MemberProductWish.wish(memberId, 101L, PAST));

        // when
        ResultActions result = mockMvc.perform(asBuyer(delete(WISHES_URL + "/101")));

        // then
        result.andExpect(status().isNoContent());
        MemberProductWish sut = wishRepository.findByMemberIdAndProductId(memberId, 101L).orElse(null);
        assertThat(sut).isNotNull();
        assertThat(sut.getDeletedAt()).isNotNull();
    }

    @Test
    void 찜하지_않은_상품을_해제해도_204를_받고_행을_만들지_않는다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(asBuyer(delete(WISHES_URL + "/101")));

        // then
        result.andExpect(status().isNoContent());
        assertThat(wishRepository.count()).isZero();
    }

    @Test
    void 해제한_상품을_다시_찜하면_같은_행이_되살아나_목록_맨_앞에_나온다() throws Exception {
        // given
        Long revivedId = wishRepository.saveAndFlush(MemberProductWish.wish(memberId, 101L, PAST)).getId();
        wishRepository.saveAndFlush(MemberProductWish.wish(memberId, 102L, PAST.plusDays(1)));
        mockMvc.perform(asBuyer(delete(WISHES_URL + "/101"))).andExpect(status().isNoContent());

        // when
        mockMvc.perform(asBuyer(put(WISHES_URL + "/101"))).andExpect(status().isNoContent());

        // then
        MemberProductWish sut = wishRepository.findByMemberIdAndProductId(memberId, 101L).orElse(null);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getId()).isEqualTo(revivedId);
            softly.assertThat(sut.getDeletedAt()).isNull();
            softly.assertThat(sut.getWishedAt()).isAfter(PAST.plusDays(1));
        });
        assertThat(wishRepository.count()).isEqualTo(2);
        mockMvc.perform(asBuyer(get(WISHES_URL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].productId").value(101))
                .andExpect(jsonPath("$.content[1].productId").value(102));
    }

    @Test
    void 목록에는_내_찜_중_해제하지_않은_것만_최근_순으로_나온다() throws Exception {
        // given
        wishRepository.saveAndFlush(MemberProductWish.wish(memberId, 101L, PAST));
        wishRepository.saveAndFlush(MemberProductWish.wish(memberId, 102L, PAST.plusDays(2)));
        MemberProductWish unwished = MemberProductWish.wish(memberId, 103L, PAST.plusDays(3));
        unwished.unwish(PAST.plusDays(4));
        wishRepository.saveAndFlush(unwished);
        wishRepository.saveAndFlush(MemberProductWish.wish(memberId, 104L, PAST.plusDays(1)));
        wishRepository.saveAndFlush(MemberProductWish.wish(otherMemberId, 105L, PAST.plusDays(5)));

        // when
        ResultActions result = mockMvc.perform(asBuyer(get(WISHES_URL)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].productId").value(102))
                .andExpect(jsonPath("$.content[0].wishedAt").value("2026-01-03T09:00:00"))
                .andExpect(jsonPath("$.content[1].productId").value(104))
                .andExpect(jsonPath("$.content[2].productId").value(101))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void 같은_시각에_찜한_항목은_나중에_만든_행이_먼저_나와_페이지_사이에서_겹치지_않는다() throws Exception {
        // given
        wishRepository.saveAndFlush(MemberProductWish.wish(memberId, 101L, PAST));
        wishRepository.saveAndFlush(MemberProductWish.wish(memberId, 102L, PAST));
        wishRepository.saveAndFlush(MemberProductWish.wish(memberId, 103L, PAST));

        // when
        ResultActions firstPage = mockMvc.perform(asBuyer(get(WISHES_URL).param("page", "0").param("size", "2")));
        ResultActions secondPage = mockMvc.perform(asBuyer(get(WISHES_URL).param("page", "1").param("size", "2")));

        // then
        firstPage.andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].productId").value(103))
                .andExpect(jsonPath("$.content[1].productId").value(102))
                .andExpect(jsonPath("$.totalPages").value(2));
        secondPage.andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].productId").value(101));
    }

    @Test
    void 같은_회원과_상품으로_행을_두_번_넣으면_유니크_제약에_걸린다() {
        // given
        wishRepository.saveAndFlush(MemberProductWish.wish(memberId, 101L, PAST));

        // when & then
        assertThatThrownBy(() -> wishRepository.saveAndFlush(MemberProductWish.wish(memberId, 101L, PAST.plusDays(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 로그인하지_않으면_401을_받는다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(put(WISHES_URL + "/101"));

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자는_403을_받는다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(put(WISHES_URL + "/101")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "ADMIN")));

        // then
        result.andExpect(status().isForbidden());
    }

    @Test
    void 상품_ID가_0_이하면_400을_받는다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(asBuyer(put(WISHES_URL + "/0")));

        // then
        result.andExpect(status().isBadRequest());
        assertThat(wishRepository.count()).isZero();
    }

    @Test
    void 페이지_크기가_50을_넘으면_422를_받는다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(asBuyer(get(WISHES_URL).param("size", "51")));

        // then
        result.andExpect(status().is(422));
    }

    @Test
    void 페이지_번호가_10000을_넘으면_500이_아니라_422를_받는다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(asBuyer(get(WISHES_URL).param("page", "50000000").param("size", "50")));

        // then
        result.andExpect(status().is(422));
    }

    private MockHttpServletRequestBuilder asBuyer(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "BUYER"));
    }

    private void cleanUp() {
        wishRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

}
