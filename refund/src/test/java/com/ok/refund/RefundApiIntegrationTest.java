package com.ok.refund;

import com.ok.refund.domain.Refund;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundableOrderItem;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeRefundOrderClient;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 구매자·판매자 환불 API를 호출해 응답과 저장된 값을 함께 확인한다. 주문은 Fake가 대신한다.
// 예시: 구매자 1이 판매자 5의 양말(주문 상품 101) 3개와 판매자 6의 모자(주문 상품 102) 1개를 한 주문(10)으로 샀다
class RefundApiIntegrationTest extends AbstractIntegrationTest {

    private static final String BUYER_URL = "/api/v1/refunds";
    private static final String SELLER_URL = "/api/v1/seller/refunds";
    private static final Long BUYER_ID = 1L;
    private static final Long OTHER_MEMBER_ID = 999L;
    private static final Long ORDER_ID = 10L;
    private static final Long SOCKS_ORDER_ITEM_ID = 101L;
    private static final Long HAT_ORDER_ITEM_ID = 102L;
    private static final Long SOCKS_SELLER_ID = 5L;
    private static final Long HAT_SELLER_ID = 6L;

    @Autowired
    WebApplicationContext context;
    @Autowired
    TestAccessTokens tokens;
    @Autowired
    RefundRepository refundRepository;
    @Autowired
    FakeRefundOrderClient orderClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 보안 필터를 적용해야 토큰 검증과 401·403 처리가 실제와 같게 동작한다
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        orderClient.put(socks("PREPARING"));
        orderClient.put(hat());
    }

    @AfterEach
    void tearDown() {
        refundRepository.deleteAllInBatch();
        // 통합 테스트는 컨텍스트(Fake 빈)를 함께 쓰므로 비우지 않으면 다른 테스트로 값이 넘어간다
        orderClient.clear();
    }

    // 구매자 요청

    @Test
    void 요청하면_201과_요청됨을_돌려주고_주문에서_받은_값으로_저장한다() throws Exception {
        // when
        ResultActions sut = request(SOCKS_ORDER_ITEM_ID, 2);

        // then
        sut.andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundId", notNullValue()))
                .andExpect(jsonPath("$.orderId", is(10)))
                .andExpect(jsonPath("$.orderItemId", is(101)))
                .andExpect(jsonPath("$.productName", is("양말")))
                .andExpect(jsonPath("$.optionName", is("흰색")))
                .andExpect(jsonPath("$.quantity", is(2)))
                .andExpect(jsonPath("$.unitPrice", is(10000)))
                .andExpect(jsonPath("$.itemAmount", is(20000)))
                .andExpect(jsonPath("$.cancelReason", nullValue()))
                .andExpect(jsonPath("$.status", is("REQUESTED")))
                .andExpect(jsonPath("$.requestedAt", notNullValue()));
        Refund saved = onlyRefund();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getBuyerId()).isEqualTo(BUYER_ID);
            softly.assertThat(saved.getSellerId()).isEqualTo(SOCKS_SELLER_ID);
            softly.assertThat(saved.getProductOptionId()).isEqualTo(55L);
            softly.assertThat(saved.getActiveOrderItemId()).isEqualTo(SOCKS_ORDER_ITEM_ID);
        });
    }

    @Test
    void 취소_사유를_적으면_앞뒤_공백을_지우고_저장한다() throws Exception {
        // when
        ResultActions sut = requestWithReason("\"  옵션을 잘못 골랐습니다.  \"");

        // then
        sut.andExpect(status().isCreated())
                .andExpect(jsonPath("$.cancelReason", is("옵션을 잘못 골랐습니다.")));
        assertThat(onlyRefund().getCancelReason()).isEqualTo("옵션을 잘못 골랐습니다.");
    }

    @Test
    void 취소_사유가_공백뿐이면_적지_않은_것으로_저장한다() throws Exception {
        // when
        ResultActions sut = requestWithReason("\"   \"");

        // then
        sut.andExpect(status().isCreated())
                .andExpect(jsonPath("$.cancelReason", nullValue()));
        assertThat(onlyRefund().getCancelReason()).isNull();
    }

    @Test
    void 취소_사유가_255자를_넘으면_422() throws Exception {
        // when & then
        requestWithReason("\"" + "가".repeat(256) + "\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
        assertThat(refundRepository.count()).isZero();
    }

    @Test
    void 개수가_1_미만이면_422() throws Exception {
        // when & then
        request(SOCKS_ORDER_ITEM_ID, 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
        assertThat(refundRepository.count()).isZero();
    }

    @Test
    void 산_개수보다_많으면_409() throws Exception {
        // when & then
        request(SOCKS_ORDER_ITEM_ID, 4)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/refund/refund-quantity-exceeded")));
        assertThat(refundRepository.count()).isZero();
    }

    @Test
    void 같은_상품에_진행_중인_요청이_있으면_409() throws Exception {
        // given
        request(SOCKS_ORDER_ITEM_ID, 1).andExpect(status().isCreated());

        // when & then
        request(SOCKS_ORDER_ITEM_ID, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/refund/refund-already-in-progress")));
        assertThat(refundRepository.count()).isOne();
    }

    @Test
    void 남의_주문_상품이면_404() throws Exception {
        // when & then
        mockMvc.perform(post(BUYER_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(OTHER_MEMBER_ID, "BUYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SOCKS_ORDER_ITEM_ID, 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/refund/order-item-not-found")));
        assertThat(refundRepository.count()).isZero();
    }

    @Test
    void 주문에_없는_상품이면_404() throws Exception {
        // given
        orderClient.clear();

        // when & then
        request(SOCKS_ORDER_ITEM_ID, 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/refund/order-item-not-found")));
        assertThat(refundRepository.count()).isZero();
    }

    @Test
    void 출고된_상품이면_409() throws Exception {
        // given
        orderClient.put(socks("SHIPPING"));

        // when & then
        request(SOCKS_ORDER_ITEM_ID, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/refund/order-item-not-cancellable")));
        assertThat(refundRepository.count()).isZero();
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        // when & then
        mockMvc.perform(post(BUYER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SOCKS_ORDER_ITEM_ID, 1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 구매_권한이_없으면_403() throws Exception {
        // when & then
        mockMvc.perform(post(BUYER_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SOCKS_ORDER_ITEM_ID, 1)))
                .andExpect(status().isForbidden());
    }

    // 구매자 목록

    @Test
    void 구매자_목록은_본인_요청만_최신순으로_보인다() throws Exception {
        // given
        request(SOCKS_ORDER_ITEM_ID, 1).andExpect(status().isCreated());
        request(HAT_ORDER_ITEM_ID, 1).andExpect(status().isCreated());

        // when
        ResultActions sut = mockMvc.perform(get(BUYER_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")));

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.size", is(25)))
                .andExpect(jsonPath("$.content[0].orderItemId", is(102)))
                .andExpect(jsonPath("$.content[1].orderItemId", is(101)));
    }

    @Test
    void 다른_구매자의_목록에는_보이지_않는다() throws Exception {
        // given
        request(SOCKS_ORDER_ITEM_ID, 1).andExpect(status().isCreated());

        // when & then
        mockMvc.perform(get(BUYER_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(OTHER_MEMBER_ID, "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    @Test
    void 페이지_번호가_음수면_422() throws Exception {
        // when & then
        mockMvc.perform(get(BUYER_URL).param("page", "-1")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    // 판매자 목록

    @Test
    void 판매자_목록은_본인_상품의_요청만_보인다() throws Exception {
        // given
        request(SOCKS_ORDER_ITEM_ID, 1).andExpect(status().isCreated());
        request(HAT_ORDER_ITEM_ID, 1).andExpect(status().isCreated());

        // when
        ResultActions sut = mockMvc.perform(get(SELLER_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SOCKS_SELLER_ID, "BUYER", "SELLER")));

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].orderItemId", is(101)))
                .andExpect(jsonPath("$.content[0].status", is("REQUESTED")));
    }

    @Test
    void 판매자_목록을_상태로_거를_수_있다() throws Exception {
        // given
        request(SOCKS_ORDER_ITEM_ID, 1).andExpect(status().isCreated());

        // when & then
        mockMvc.perform(get(SELLER_URL).param("status", "REQUESTED")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SOCKS_SELLER_ID, "BUYER", "SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void 없는_상태로_거르면_422() throws Exception {
        // when & then
        mockMvc.perform(get(SELLER_URL).param("status", "DONE")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SOCKS_SELLER_ID, "BUYER", "SELLER")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    @Test
    void 판매자가_아니면_판매자_목록은_403() throws Exception {
        // when & then
        mockMvc.perform(get(SELLER_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")))
                .andExpect(status().isForbidden());
    }

    private ResultActions request(Long orderItemId, int quantity) throws Exception {
        return mockMvc.perform(post(BUYER_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(orderItemId, quantity)));
    }

    private ResultActions requestWithReason(String reasonJson) throws Exception {
        return mockMvc.perform(post(BUYER_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderId": %d, "orderItemId": %d, "quantity": 1, "cancelReason": %s}
                        """.formatted(ORDER_ID, SOCKS_ORDER_ITEM_ID, reasonJson)));
    }

    private static String body(Long orderItemId, int quantity) {
        return """
                {"orderId": %d, "orderItemId": %d, "quantity": %d}
                """.formatted(ORDER_ID, orderItemId, quantity);
    }

    private Refund onlyRefund() {
        List<Refund> refunds = refundRepository.findAll();
        assertThat(refunds).hasSize(1);
        return refunds.getFirst();
    }

    private static RefundableOrderItem socks(String status) {
        return new RefundableOrderItem(ORDER_ID, SOCKS_ORDER_ITEM_ID, BUYER_ID, SOCKS_SELLER_ID, 55L, "양말", "흰색", status,
                10_000L, 3);
    }

    private static RefundableOrderItem hat() {
        return new RefundableOrderItem(ORDER_ID, HAT_ORDER_ITEM_ID, BUYER_ID, HAT_SELLER_ID, 66L, "모자", "검정", "PREPARING",
                5_000L, 1);
    }
}
