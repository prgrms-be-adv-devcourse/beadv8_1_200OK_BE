package com.ok.order;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 주문·장바구니 API가 구매 권한(BUYER)이 있는 토큰만 받는지 실제 보안 필터로 확인한다.
// @PreAuthorize를 컨트롤러 클래스에 붙였으므로, 다른 모듈 호출이 없는 조회 API를 컨트롤러마다 하나씩 대표로 부른다.
class BuyerRoleApiIntegrationTest extends AbstractIntegrationTest {
    private static final Long MEMBER_ID = 9_358L;
    private static final String CART_URL = "/api/v1/cart";
    private static final String ORDERS_URL = "/api/v1/orders";

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // 장바구니 조회는 장바구니가 없으면 만들므로 테스트 회원의 장바구니를 지운다
    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".cart WHERE member_id = ?", MEMBER_ID);
    }

    @ParameterizedTest
    @MethodSource("주문_장바구니_조회_API")
    void 구매자_토큰으로_쓸_수_있다(String url) throws Exception {
        // when & then
        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER")))
                .andExpect(status().isOk());
    }

    static Stream<Arguments> 주문_장바구니_조회_API() {
        return Stream.of(Arguments.of(CART_URL), Arguments.of(ORDERS_URL));
    }

    // 승인된 판매자 토큰에는 BUYER가 함께 담기므로 다른 판매자의 상품을 살 수 있어야 한다
    @ParameterizedTest
    @MethodSource("주문_장바구니_조회_API")
    void 판매자_토큰으로도_쓸_수_있다(String url) throws Exception {
        // when & then
        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER", "SELLER")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @MethodSource("주문_장바구니_조회_API")
    void 구매_권한이_없는_관리자_토큰은_403(String url) throws Exception {
        // when & then
        mockMvc.perform(get(url)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 토큰_없이_부르면_401() throws Exception {
        // when & then
        mockMvc.perform(get(CART_URL))
                .andExpect(status().isUnauthorized());
    }
}
