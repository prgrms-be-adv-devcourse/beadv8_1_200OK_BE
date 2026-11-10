package com.ok.refund;

import com.jayway.jsonpath.JsonPath;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundableOrderItem;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeRefundOrderClient;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    private static final Long FREE_SELLER_ID = 7L;
    private static final Long OTHER_SELLER_ID = 9L;
    private static final Long FREE_ORDER_ID = 20L;
    private static final Long FREE_A_ORDER_ITEM_ID = 201L;
    private static final Long FREE_B_ORDER_ITEM_ID = 202L;

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
                .andExpect(jsonPath("$.shippingFeeAmount", is(0)))
                .andExpect(jsonPath("$.remoteAreaFeeAmount", is(0)))
                .andExpect(jsonPath("$.deductedShippingFeeAmount", is(0)))
                .andExpect(jsonPath("$.refundAmount", is(20000)))
                .andExpect(jsonPath("$.cancelReason", nullValue()))
                .andExpect(jsonPath("$.status", is("REQUESTED")))
                .andExpect(jsonPath("$.rejectReason", nullValue()))
                .andExpect(jsonPath("$.requestedAt", notNullValue()))
                .andExpect(jsonPath("$.decidedAt", nullValue()));
        Refund saved = onlyRefund();
        assertThat(saved).isNotNull();
        // 응답은 저장한 객체로 만들어 DB 값과 다를 수 있으므로, 다시 읽은 행의 모든 칸을 따로 확인한다
        assertSoftly(softly -> {
            softly.assertThat(saved.getOrderId()).isEqualTo(ORDER_ID);
            softly.assertThat(saved.getOrderItemId()).isEqualTo(SOCKS_ORDER_ITEM_ID);
            softly.assertThat(saved.getQuantity()).isEqualTo(2);
            softly.assertThat(saved.getBuyerId()).isEqualTo(BUYER_ID);
            softly.assertThat(saved.getSellerId()).isEqualTo(SOCKS_SELLER_ID);
            softly.assertThat(saved.getProductOptionId()).isEqualTo(55L);
            softly.assertThat(saved.getProductName()).isEqualTo("양말");
            softly.assertThat(saved.getOptionName()).isEqualTo("흰색");
            softly.assertThat(saved.getUnitPrice()).isEqualTo(10_000L);
            softly.assertThat(saved.getItemAmount()).isEqualTo(20_000L);
            softly.assertThat(saved.getShippingFeeAmount()).isZero();
            softly.assertThat(saved.getRemoteAreaFeeAmount()).isZero();
            softly.assertThat(saved.getDeductedShippingFeeAmount()).isZero();
            softly.assertThat(saved.getCancelReason()).isNull();
            softly.assertThat(saved.getStatus()).isEqualTo(RefundStatus.REQUESTED);
            softly.assertThat(saved.getRejectReason()).isNull();
            softly.assertThat(saved.getDecidedAt()).isNull();
            softly.assertThat(saved.getActiveOrderItemId()).isEqualTo(SOCKS_ORDER_ITEM_ID);
            softly.assertThat(saved.getCreatedAt()).isNotNull();
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
    void 취소_사유는_255자까지_저장한다() throws Exception {
        // when & then: DB 칸(VARCHAR(255))과 검증 길이가 같아야 경계 값이 DB에서 잘리거나 실패하지 않는다
        requestWithReason("\"" + "가".repeat(255) + "\"")
                .andExpect(status().isCreated());
        assertThat(onlyRefund().getCancelReason()).hasSize(255);
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
    void 주문_호출이_실패하면_503이고_저장하지_않는다() throws Exception {
        // given
        orderClient.fail();

        // when & then
        request(SOCKS_ORDER_ITEM_ID, 1)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type", endsWith("/refund/order-api-failed")));
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
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(25)))
                .andExpect(jsonPath("$.totalPages", is(1)))
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
        // given: 양말 요청을 철회하고 다시 요청해, 같은 판매자에게 철회 1건과 요청됨 1건이 있다
        Long withdrawnId = requestedRefundId(SOCKS_ORDER_ITEM_ID);
        withdraw(BUYER_ID, withdrawnId).andExpect(status().isOk());
        Long requestedId = requestedRefundId(SOCKS_ORDER_ITEM_ID);

        // when & then
        mockMvc.perform(get(SELLER_URL).param("status", "REQUESTED")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SOCKS_SELLER_ID, "BUYER", "SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].refundId", is(requestedId.intValue())));
        mockMvc.perform(get(SELLER_URL).param("status", "WITHDRAWN")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SOCKS_SELLER_ID, "BUYER", "SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].refundId", is(withdrawnId.intValue())));
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

    // 판매자 승인·거절

    @Test
    void 승인하면_200과_처리_중을_돌려주고_같은_상품의_새_요청은_계속_막는다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);

        // when
        ResultActions sut = decide(SOCKS_SELLER_ID, refundId, """
                {"decision": "APPROVE"}
                """);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.refundId", is(refundId.intValue())))
                .andExpect(jsonPath("$.status", is("PROCESSING")))
                .andExpect(jsonPath("$.rejectReason", nullValue()))
                .andExpect(jsonPath("$.decidedAt", notNullValue()));
        Refund saved = onlyRefund();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(saved.getDecidedAt()).isNotNull();
            softly.assertThat(saved.getActiveOrderItemId()).isEqualTo(SOCKS_ORDER_ITEM_ID);
            softly.assertThat(saved.getItemAmount()).isEqualTo(10_000L);
        });
        // 처리 중은 아직 끝나지 않았으므로 같은 상품에 두 번째 요청을 받지 않는다
        request(SOCKS_ORDER_ITEM_ID, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/refund/refund-already-in-progress")));
    }

    @Test
    void 거절하면_사유를_저장하고_같은_상품에_다시_요청할_수_있다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);

        // when
        ResultActions sut = decide(SOCKS_SELLER_ID, refundId, """
                {"decision": "REJECT", "rejectReason": "  이미 포장을 마쳤습니다.  "}
                """);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.rejectReason", is("이미 포장을 마쳤습니다.")))
                .andExpect(jsonPath("$.decidedAt", notNullValue()));
        Refund saved = refundRepository.findById(refundId).orElseThrow();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getStatus()).isEqualTo(RefundStatus.REJECTED);
            softly.assertThat(saved.getRejectReason()).isEqualTo("이미 포장을 마쳤습니다.");
            softly.assertThat(saved.getDecidedAt()).isNotNull();
            softly.assertThat(saved.getActiveOrderItemId()).isNull();
        });
        request(SOCKS_ORDER_ITEM_ID, 1).andExpect(status().isCreated());
        assertThat(refundRepository.count()).isEqualTo(2);
    }

    @Test
    void 거절_사유가_공백뿐이면_422이고_바꾸지_않는다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);

        // when & then
        decide(SOCKS_SELLER_ID, refundId, """
                {"decision": "REJECT", "rejectReason": "   "}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/refund/reject-reason-required")));
        assertThat(onlyRefund().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 거절_사유가_255자를_넘으면_422() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);

        // when & then
        decide(SOCKS_SELLER_ID, refundId, """
                {"decision": "REJECT", "rejectReason": "%s"}
                """.formatted("가".repeat(256)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
        assertThat(onlyRefund().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 거절_사유는_255자까지_저장한다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);

        // when & then: DB 칸(VARCHAR(255))과 검증 길이가 같아야 경계 값이 DB에서 잘리거나 실패하지 않는다
        decide(SOCKS_SELLER_ID, refundId, """
                {"decision": "REJECT", "rejectReason": "%s"}
                """.formatted("가".repeat(255)))
                .andExpect(status().isOk());
        assertThat(onlyRefund().getRejectReason()).hasSize(255);
    }

    // 결정 값이 없을 때 422를 내는 것은 @NotBlank뿐이다(@Pattern은 null을 통과시킨다). 빠지면 valueOf(null)로 500이 된다
    @ParameterizedTest
    @MethodSource("승인_거절이_아닌_결정_본문")
    void 결정_값이_승인_거절이_아니면_422(String json) throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);

        // when & then
        decide(SOCKS_SELLER_ID, refundId, json)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
        assertThat(onlyRefund().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    static Stream<String> 승인_거절이_아닌_결정_본문() {
        return Stream.of(
                """
                {"decision": "HOLD"}
                """,
                """
                {"decision": ""}
                """,
                """
                {}
                """);
    }

    @Test
    void 남의_상품_요청을_결정하면_404이고_바꾸지_않는다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);

        // when & then: 모자 판매자가 양말 요청을 승인하려 한다
        decide(HAT_SELLER_ID, refundId, """
                {"decision": "APPROVE"}
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/refund/refund-not-found")));
        assertThat(onlyRefund().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 이미_거절한_요청을_승인하면_409() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);
        decide(SOCKS_SELLER_ID, refundId, """
                {"decision": "REJECT", "rejectReason": "재고 확인 필요"}
                """).andExpect(status().isOk());

        // when & then
        decide(SOCKS_SELLER_ID, refundId, """
                {"decision": "APPROVE"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/refund/invalid-refund-state")));
        assertThat(onlyRefund().getStatus()).isEqualTo(RefundStatus.REJECTED);
    }

    @Test
    void 판매자가_아니면_결정은_403() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);

        // when & then
        mockMvc.perform(patch(SELLER_URL + "/" + refundId)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SOCKS_SELLER_ID, "BUYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision": "APPROVE"}
                                """))
                .andExpect(status().isForbidden());
        assertThat(onlyRefund().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    // 배송비 (#410)
    // 무료배송 묶음 예시: 판매자 7이 주문 20에 A(201) 30,000원 + B(202) 30,000원 = 60,000원을 팔아 배송비 0원,
    // 도서산간 운임 2,000원. 기본 배송비 3,000원, 50,000원 이상 무료배송. 구매자가 낸 돈은 62,000원이다

    @Test
    void 나눠서_전부_취소하면_돌려준_합계가_결제_금액과_같다() throws Exception {
        // given: B를 먼저 취소하면 30,000원이 남아 무료배송이 깨져 3,000원을 뺀다
        putFreeBundle();
        Long first = requestedRefundId(FREE_ORDER_ID, FREE_B_ORDER_ITEM_ID);
        approve(FREE_SELLER_ID, first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PROCESSING")))
                .andExpect(jsonPath("$.shippingFeeAmount", is(0)))
                .andExpect(jsonPath("$.remoteAreaFeeAmount", is(0)))
                .andExpect(jsonPath("$.deductedShippingFeeAmount", is(3000)))
                .andExpect(jsonPath("$.refundAmount", is(27000)));
        Long last = requestedRefundId(FREE_ORDER_ID, FREE_A_ORDER_ITEM_ID);

        // when: A까지 취소하면 묶음 전부 취소라 앞에서 뺀 3,000원과 도서산간 운임 2,000원을 함께 돌려준다
        ResultActions sut = approve(FREE_SELLER_ID, last);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.refundId", is(last.intValue())))
                .andExpect(jsonPath("$.orderId", is(20)))
                .andExpect(jsonPath("$.orderItemId", is(201)))
                .andExpect(jsonPath("$.productName", is("셔츠")))
                .andExpect(jsonPath("$.optionName", is("기본")))
                .andExpect(jsonPath("$.quantity", is(1)))
                .andExpect(jsonPath("$.unitPrice", is(30000)))
                .andExpect(jsonPath("$.itemAmount", is(30000)))
                .andExpect(jsonPath("$.shippingFeeAmount", is(3000)))
                .andExpect(jsonPath("$.remoteAreaFeeAmount", is(2000)))
                .andExpect(jsonPath("$.deductedShippingFeeAmount", is(0)))
                .andExpect(jsonPath("$.refundAmount", is(35000)))
                .andExpect(jsonPath("$.cancelReason", nullValue()))
                .andExpect(jsonPath("$.status", is("PROCESSING")))
                .andExpect(jsonPath("$.rejectReason", nullValue()))
                .andExpect(jsonPath("$.requestedAt", notNullValue()))
                .andExpect(jsonPath("$.decidedAt", notNullValue()));
        Refund firstSaved = refundRepository.findById(first).orElseThrow();
        Refund lastSaved = refundRepository.findById(last).orElseThrow();
        assertThat(lastSaved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(lastSaved.getShippingFeeAmount()).isEqualTo(3_000L);
            softly.assertThat(lastSaved.getRemoteAreaFeeAmount()).isEqualTo(2_000L);
            softly.assertThat(lastSaved.getDeductedShippingFeeAmount()).isZero();
            softly.assertThat(firstSaved.getDeductedShippingFeeAmount()).isEqualTo(3_000L);
            // 27,000 + 35,000 = 62,000원. 상자가 나가지 않았으니 구매자가 낸 돈을 모두 돌려받는다
            softly.assertThat(firstSaved.getRefundAmount() + lastSaved.getRefundAmount()).isEqualTo(62_000L);
        });
    }

    @Test
    void 요청_때_배송비가_모자라면_409이고_저장하지_않는다() throws Exception {
        // given: 1,000원짜리만 취소하면 49,500원이 남아 무료배송이 깨지는데, 뺄 3,000원이 취소 금액보다 크다
        BundleShipping bundle = new BundleShipping(0, 0, 3_000L, 50_000L,
                List.of(new BundleItem(301L, 1_000L, 1), new BundleItem(302L, 49_500L, 1)));
        orderClient.put(orderItem(30L, 301L, FREE_SELLER_ID, 1_000L, 1, bundle));

        // when & then
        request(30L, 301L, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/refund/partial-cancel-not-allowed")));
        assertThat(refundRepository.count()).isZero();
    }

    @Test
    void 승인_때_배송비가_모자라면_사유와_함께_자동_거절로_200() throws Exception {
        // given: 기본 배송비 3,000원, 10,000원 이상 무료배송. A 2,000 + B 8,500 + C 2,500 = 13,000원.
        // A·C 요청은 각각 따로 보면 기준 이상이 남아 통과하지만, C를 먼저 승인하면 A 승인 때 8,500원만 남아 3,000원을 빼야 한다
        BundleShipping bundle = new BundleShipping(0, 0, 3_000L, 10_000L, List.of(
                new BundleItem(401L, 2_000L, 1), new BundleItem(402L, 8_500L, 1), new BundleItem(403L, 2_500L, 1)));
        orderClient.put(orderItem(40L, 401L, FREE_SELLER_ID, 2_000L, 1, bundle));
        orderClient.put(orderItem(40L, 403L, FREE_SELLER_ID, 2_500L, 1, bundle));
        Long refundA = requestedRefundId(40L, 401L);
        Long refundC = requestedRefundId(40L, 403L);
        approve(FREE_SELLER_ID, refundC)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PROCESSING")))
                .andExpect(jsonPath("$.deductedShippingFeeAmount", is(0)));

        // when
        ResultActions sut = approve(FREE_SELLER_ID, refundA);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.rejectReason", is("취소 금액이 남은 상품의 배송비보다 적어 이 상품만 취소할 수 없습니다.")))
                .andExpect(jsonPath("$.deductedShippingFeeAmount", is(0)))
                .andExpect(jsonPath("$.refundAmount", is(2000)));
        Refund saved = refundRepository.findById(refundA).orElseThrow();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getStatus()).isEqualTo(RefundStatus.REJECTED);
            softly.assertThat(saved.getDecidedAt()).isNotNull();
            softly.assertThat(saved.getActiveOrderItemId()).isNull();
            softly.assertThat(saved.getDeductedShippingFeeAmount()).isZero();
        });
    }

    @Test
    void 승인_때_주문_호출이_실패하면_503이고_요청됨으로_남는다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);
        orderClient.fail();

        // when & then
        approve(SOCKS_SELLER_ID, refundId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type", endsWith("/refund/order-api-failed")));
        Refund saved = onlyRefund();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getStatus()).isEqualTo(RefundStatus.REQUESTED);
            softly.assertThat(saved.getDecidedAt()).isNull();
        });
    }

    // 요청은 출고 전에 했지만 판매자가 승인하기 전에 출고했다
    @Test
    void 승인_때_출고된_상품이면_사유와_함께_자동_거절된다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);
        orderClient.put(socks("SHIPPING"));

        // when & then
        approve(SOCKS_SELLER_ID, refundId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.rejectReason", is("이미 출고됐거나 취소된 상품이라 취소할 수 없습니다.")));
        Refund saved = onlyRefund();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getStatus()).isEqualTo(RefundStatus.REJECTED);
            softly.assertThat(saved.getDecidedAt()).isNotNull();
            softly.assertThat(saved.getActiveOrderItemId()).isNull();
        });
    }

    @Test
    void 승인_때_주문에_상품이_없으면_404이고_요청됨으로_남는다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);
        orderClient.clear();

        // when & then
        approve(SOCKS_SELLER_ID, refundId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/refund/order-item-not-found")));
        assertThat(onlyRefund().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 거절은_주문이_실패해도_된다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);
        orderClient.fail();

        // when & then
        decide(SOCKS_SELLER_ID, refundId, """
                {"decision": "REJECT", "rejectReason": "재고 확인 필요"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")));
    }

    // 다른 주문·다른 판매자·요청됨·철회는 이미 취소된 것으로 세지 않아야 한다. 잘못 세면 A 승인 때 기준이 이미 깨진 것으로 보여
    // 배송비를 빼지 않거나, 묶음에 없는 상품이 섞여 500이 난다
    @Test
    void 같은_묶음의_처리_중_요청만_집계한다() throws Exception {
        // given
        putFreeBundle();
        BundleShipping sameSellerOtherOrder = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(211L, 10_000L, 1)));
        orderClient.put(orderItem(21L, 211L, FREE_SELLER_ID, 10_000L, 1, sameSellerOtherOrder));
        BundleShipping otherSellerSameOrder = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(221L, 10_000L, 1)));
        orderClient.put(orderItem(FREE_ORDER_ID, 221L, OTHER_SELLER_ID, 10_000L, 1, otherSellerSameOrder));
        approve(FREE_SELLER_ID, requestedRefundId(21L, 211L)).andExpect(status().isOk());
        approve(OTHER_SELLER_ID, requestedRefundId(FREE_ORDER_ID, 221L)).andExpect(status().isOk());
        withdraw(BUYER_ID, requestedRefundId(FREE_ORDER_ID, FREE_B_ORDER_ITEM_ID)).andExpect(status().isOk());
        requestedRefundId(FREE_ORDER_ID, FREE_B_ORDER_ITEM_ID);
        Long target = requestedRefundId(FREE_ORDER_ID, FREE_A_ORDER_ITEM_ID);

        // when: B는 요청됨이라 세지 않으므로 A만 취소한 것으로 보고, 30,000원이 남아 처음 기준이 깨진다
        ResultActions sut = approve(FREE_SELLER_ID, target);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PROCESSING")))
                .andExpect(jsonPath("$.shippingFeeAmount", is(0)))
                .andExpect(jsonPath("$.deductedShippingFeeAmount", is(3000)))
                .andExpect(jsonPath("$.refundAmount", is(27000)));
    }

    // 구매자 철회

    @Test
    void 철회하면_200과_철회를_돌려주고_같은_상품에_다시_요청할_수_있다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);

        // when
        ResultActions sut = withdraw(BUYER_ID, refundId);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("WITHDRAWN")))
                .andExpect(jsonPath("$.decidedAt", notNullValue()));
        Refund saved = refundRepository.findById(refundId).orElseThrow();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getStatus()).isEqualTo(RefundStatus.WITHDRAWN);
            softly.assertThat(saved.getDecidedAt()).isNotNull();
            softly.assertThat(saved.getActiveOrderItemId()).isNull();
        });
        request(SOCKS_ORDER_ITEM_ID, 1).andExpect(status().isCreated());
        assertThat(refundRepository.count()).isEqualTo(2);
    }

    @Test
    void 승인된_뒤에는_철회할_수_없다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);
        decide(SOCKS_SELLER_ID, refundId, """
                {"decision": "APPROVE"}
                """).andExpect(status().isOk());

        // when & then
        withdraw(BUYER_ID, refundId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/refund/invalid-refund-state")));
        assertThat(onlyRefund().getStatus()).isEqualTo(RefundStatus.PROCESSING);
    }

    @Test
    void 남의_요청을_철회하면_404이고_바꾸지_않는다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);

        // when & then
        withdraw(OTHER_MEMBER_ID, refundId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/refund/refund-not-found")));
        assertThat(onlyRefund().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 구매_권한이_없으면_철회는_403이고_바꾸지_않는다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);

        // when & then
        mockMvc.perform(post(BUYER_URL + "/" + refundId + "/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "ADMIN")))
                .andExpect(status().isForbidden());
        assertThat(onlyRefund().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    // 동시 처리

    // 행 잠금이 없으면 두 요청이 모두 "요청됨"을 읽고 둘 다 성공해, 나중에 저장한 쪽이 앞의 결과를 덮어쓴다
    @Test
    void 승인과_철회가_동시에_오면_하나만_성공한다() throws Exception {
        // given
        Long refundId = requestedRefundId(SOCKS_ORDER_ITEM_ID);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> approve = () -> {
            start.await();
            return decide(SOCKS_SELLER_ID, refundId, """
                    {"decision": "APPROVE"}
                    """).andReturn().getResponse().getStatus();
        };
        Callable<Integer> withdraw = () -> {
            start.await();
            return withdraw(BUYER_ID, refundId).andReturn().getResponse().getStatus();
        };

        // when
        List<Integer> statuses = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> approveResult = executor.submit(approve);
            Future<Integer> withdrawResult = executor.submit(withdraw);
            start.countDown();
            statuses.add(approveResult.get());
            statuses.add(withdrawResult.get());
        } finally {
            executor.shutdownNow();
        }

        // then
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        // 성공한 쪽의 결과가 남아야 한다. 승인이 이겼으면 처리 중, 철회가 이겼으면 철회다
        RefundStatus expected = statuses.getFirst() == 200 ? RefundStatus.PROCESSING : RefundStatus.WITHDRAWN;
        assertThat(onlyRefund().getStatus()).isEqualTo(expected);
    }

    private ResultActions request(Long orderItemId, int quantity) throws Exception {
        return request(ORDER_ID, orderItemId, quantity);
    }

    private ResultActions request(Long orderId, Long orderItemId, int quantity) throws Exception {
        return mockMvc.perform(post(BUYER_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderId": %d, "orderItemId": %d, "quantity": %d}
                        """.formatted(orderId, orderItemId, quantity)));
    }

    private ResultActions requestWithReason(String reasonJson) throws Exception {
        return mockMvc.perform(post(BUYER_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderId": %d, "orderItemId": %d, "quantity": 1, "cancelReason": %s}
                        """.formatted(ORDER_ID, SOCKS_ORDER_ITEM_ID, reasonJson)));
    }

    // 요청을 하나 만들고 응답의 환불 ID를 돌려준다
    private Long requestedRefundId(Long orderItemId) throws Exception {
        return requestedRefundId(ORDER_ID, orderItemId);
    }

    private Long requestedRefundId(Long orderId, Long orderItemId) throws Exception {
        String response = request(orderId, orderItemId, 1)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.refundId")).longValue();
    }

    private ResultActions approve(Long sellerId, Long refundId) throws Exception {
        return decide(sellerId, refundId, """
                {"decision": "APPROVE"}
                """);
    }

    private void putFreeBundle() {
        BundleShipping bundle = new BundleShipping(0, 2_000L, 3_000L, 50_000L, List.of(
                new BundleItem(FREE_A_ORDER_ITEM_ID, 30_000L, 1), new BundleItem(FREE_B_ORDER_ITEM_ID, 30_000L, 1)));
        orderClient.put(orderItem(FREE_ORDER_ID, FREE_A_ORDER_ITEM_ID, FREE_SELLER_ID, 30_000L, 1, bundle));
        orderClient.put(orderItem(FREE_ORDER_ID, FREE_B_ORDER_ITEM_ID, FREE_SELLER_ID, 30_000L, 1, bundle));
    }

    private static RefundableOrderItem orderItem(Long orderId, Long orderItemId, Long sellerId, long unitPrice, int quantity,
                                                 BundleShipping bundle) {
        return new RefundableOrderItem(orderId, orderItemId, BUYER_ID, sellerId, 77L, "셔츠", "기본", "PREPARING", unitPrice,
                quantity, bundle);
    }

    private ResultActions decide(Long sellerId, Long refundId, String json) throws Exception {
        return mockMvc.perform(patch(SELLER_URL + "/" + refundId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(sellerId, "BUYER", "SELLER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions withdraw(Long buyerId, Long refundId) throws Exception {
        return mockMvc.perform(post(BUYER_URL + "/" + refundId + "/withdraw")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(buyerId, "BUYER")));
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

    // 판매자가 달라 양말과 모자는 각자 한 상품짜리 묶음이다. 둘 다 무료배송 기준(50,000원) 아래라 배송비 3,000원을 냈다
    private static RefundableOrderItem socks(String status) {
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(SOCKS_ORDER_ITEM_ID, 10_000L, 3)));
        return new RefundableOrderItem(ORDER_ID, SOCKS_ORDER_ITEM_ID, BUYER_ID, SOCKS_SELLER_ID, 55L, "양말", "흰색", status,
                10_000L, 3, bundle);
    }

    private static RefundableOrderItem hat() {
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(HAT_ORDER_ITEM_ID, 5_000L, 1)));
        return new RefundableOrderItem(ORDER_ID, HAT_ORDER_ITEM_ID, BUYER_ID, HAT_SELLER_ID, 66L, "모자", "검정", "PREPARING",
                5_000L, 1, bundle);
    }
}
