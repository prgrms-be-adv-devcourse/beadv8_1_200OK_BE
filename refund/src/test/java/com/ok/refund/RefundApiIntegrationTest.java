package com.ok.refund;

import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundFailedStep;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundableOrderItem;
import com.ok.refund.service.dto.SellerGroupItem;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeRefundDeliveryClient;
import com.ok.testsupport.FakeRefundOrderClient;
import com.ok.testsupport.FakeRefundProductClient;
import com.ok.testsupport.FakeRefundWalletClient;
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

import static com.ok.refund.fixture.RefundFixture.BUYER_ID;
import static com.ok.refund.fixture.RefundFixture.HAT_ORDER_ITEM_ID;
import static com.ok.refund.fixture.RefundFixture.ORDER_ID;
import static com.ok.refund.fixture.RefundFixture.ORDER_ITEM_ID;
import static com.ok.refund.fixture.RefundFixture.SELLER_ID;
import static com.ok.refund.fixture.RefundFixture.hat;
import static com.ok.refund.fixture.RefundFixture.socks;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 구매자·판매자 환불 API를 호출해, 상태 저장과 다른 모듈(Fake)에 남은 호출 결과를 함께 확인한다.
// 예시는 설계 문서와 같다: 같은 판매자 묶음에 양말 3개(10,000원) + 모자 1개(5,000원), 배송비 3,000원, 추가 운임 3,000원, 결제 41,000원
class RefundApiIntegrationTest extends AbstractIntegrationTest {

    private static final String BUYER_URL = "/api/v1/refunds";
    private static final String SELLER_URL = "/api/v1/seller/refunds";
    private static final Long OTHER_MEMBER_ID = 999L;

    @Autowired
    WebApplicationContext context;
    @Autowired
    TestAccessTokens tokens;
    @Autowired
    RefundRepository refundRepository;
    @Autowired
    FakeRefundOrderClient orderClient;
    @Autowired
    FakeRefundDeliveryClient deliveryClient;
    @Autowired
    FakeRefundProductClient productClient;
    @Autowired
    FakeRefundWalletClient walletClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 보안 필터를 적용해야 토큰 검증과 401·403 처리가 실제와 같게 동작한다
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        orderClient.put(socks());
        orderClient.put(hat());
        deliveryClient.addShipment(ORDER_ITEM_ID);
        deliveryClient.addShipment(HAT_ORDER_ITEM_ID);
    }

    @AfterEach
    void tearDown() {
        refundRepository.deleteAllInBatch();
        // 통합 테스트는 컨텍스트(Fake 빈)를 함께 쓰므로 비우지 않으면 다른 테스트로 기록이 넘어간다
        orderClient.clear();
        deliveryClient.clear();
        productClient.clear();
        walletClient.clear();
    }

    // 구매자 요청

    @Test
    void 요청하면_201과_요청됨을_돌려주고_주문에서_받은_값으로_저장한다() throws Exception {
        // when
        ResultActions sut = request(ORDER_ITEM_ID, 1);

        // then
        sut.andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("REQUESTED")))
                .andExpect(jsonPath("$.productName", is("양말")))
                .andExpect(jsonPath("$.itemAmount", is(10000)))
                .andExpect(jsonPath("$.shippingFeeAmount", is(0)))
                .andExpect(jsonPath("$.refundAmount", is(10000)));
        Refund saved = onlyRefund();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getSellerId()).isEqualTo(SELLER_ID);
            softly.assertThat(saved.getBuyerId()).isEqualTo(BUYER_ID);
            softly.assertThat(saved.getActiveOrderItemId()).isEqualTo(ORDER_ITEM_ID);
        });
    }

    @Test
    void 같은_상품에_진행_중인_요청이_있으면_409() throws Exception {
        // given
        request(ORDER_ITEM_ID, 1).andExpect(status().isCreated());

        // when & then
        request(ORDER_ITEM_ID, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/refund/refund-already-in-progress")));
        assertThat(refundRepository.count()).isOne();
    }

    @Test
    void 개수가_1_미만이면_422() throws Exception {
        // when & then
        request(ORDER_ITEM_ID, 0)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    @Test
    void 남의_주문_상품이면_404() throws Exception {
        // when & then
        mockMvc.perform(post(BUYER_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(OTHER_MEMBER_ID, "BUYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ORDER_ITEM_ID, 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/refund/order-item-not-found")));
    }

    @Test
    void 출고된_상품은_요청할_수_없다() throws Exception {
        // given
        orderClient.put(socks("SHIPPING"));

        // when & then
        request(ORDER_ITEM_ID, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/refund/order-item-not-cancellable")));
    }

    // 철회

    @Test
    void 요청됨이면_철회할_수_있고_같은_상품에_다시_요청할_수_있다() throws Exception {
        // given
        Long refundId = requestedId(ORDER_ITEM_ID, 1);

        // when
        ResultActions sut = mockMvc.perform(post(BUYER_URL + "/{refundId}/withdraw", refundId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")));

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("WITHDRAWN")))
                .andExpect(jsonPath("$.refundAmount", is(0)));
        request(ORDER_ITEM_ID, 1).andExpect(status().isCreated());
    }

    @Test
    void 남의_요청은_철회할_수_없다() throws Exception {
        // given
        Long refundId = requestedId(ORDER_ITEM_ID, 1);

        // when & then
        mockMvc.perform(post(BUYER_URL + "/{refundId}/withdraw", refundId)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(OTHER_MEMBER_ID, "BUYER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/refund/refund-not-found")));
    }

    // 판매자 승인

    @Test
    void 승인하면_완료되고_지갑_주문_상품_배송에_한_번씩_요청한다() throws Exception {
        // given
        Long refundId = requestedId(ORDER_ITEM_ID, 1);

        // when
        ResultActions sut = decide(refundId, SELLER_ID, "APPROVE", null);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.refundAmount", is(10000)));
        assertSoftly(softly -> {
            softly.assertThat(deliveryClient.reservedRefundIds()).containsExactly(refundId);
            softly.assertThat(walletClient.refunded()).containsExactly(
                    new FakeRefundWalletClient.Refunded(refundId, ORDER_ID, BUYER_ID, 10_000L));
            softly.assertThat(orderClient.applied()).containsExactly(
                    new FakeRefundOrderClient.Applied(refundId, ORDER_ITEM_ID, 1, 0L));
            softly.assertThat(productClient.released()).containsExactly(
                    new FakeRefundProductClient.Released(refundId, ORDER_ID, 55L, 1));
            softly.assertThat(deliveryClient.confirmedRefundIds()).containsExactly(refundId);
            softly.assertThat(refundRepository.findById(refundId).orElseThrow().getActiveOrderItemId()).isNull();
        });
    }

    @Test
    void 한_요청으로_묶음_전부를_취소하면_배송비와_추가_운임을_함께_돌려준다() throws Exception {
        // given: 판매자 묶음에 양말 3개만 있는 주문
        orderClient.put(new RefundableOrderItem(ORDER_ID, ORDER_ITEM_ID, BUYER_ID, SELLER_ID, 55L, "양말", "흰색",
                RefundableOrderItem.CANCELLABLE_STATUS, 10_000L, 3, 3_000L, 3_000L, 3_000L, 50_000L,
                List.of(new SellerGroupItem(ORDER_ITEM_ID, 10_000L, 3))));
        Long refundId = requestedId(ORDER_ITEM_ID, 3);

        // when
        ResultActions sut = decide(refundId, SELLER_ID, "APPROVE", null);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingFeeAmount", is(3000)))
                .andExpect(jsonPath("$.remoteAreaFeeAmount", is(3000)))
                .andExpect(jsonPath("$.refundAmount", is(36000)));
        assertThat(walletClient.totalAmount()).isEqualTo(36_000L);
    }

    @Test
    void 승인_응답의_모든_칸이_저장한_값과_맞는다() throws Exception {
        // given: 배송비·추가 운임, 단가·상품 금액이 서로 다르게 나오도록 개수 2, 배송비 3,000원, 추가 운임 2,500원으로 둔다.
        // 같은 타입 칸이 뒤바뀌어도 잡히게 하려는 것이다
        orderClient.put(new RefundableOrderItem(ORDER_ID, ORDER_ITEM_ID, BUYER_ID, SELLER_ID, 55L, "양말", "흰색",
                RefundableOrderItem.CANCELLABLE_STATUS, 10_000L, 2, 3_000L, 2_500L, 3_000L, 50_000L,
                List.of(new SellerGroupItem(ORDER_ITEM_ID, 10_000L, 2))));
        Long refundId = requestedId(ORDER_ITEM_ID, 2);

        // when
        ResultActions sut = decide(refundId, SELLER_ID, "APPROVE", null);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.refundId", is(refundId.intValue())))
                .andExpect(jsonPath("$.orderId", is(ORDER_ID.intValue())))
                .andExpect(jsonPath("$.orderItemId", is(ORDER_ITEM_ID.intValue())))
                .andExpect(jsonPath("$.productName", is("양말")))
                .andExpect(jsonPath("$.optionName", is("흰색")))
                .andExpect(jsonPath("$.quantity", is(2)))
                .andExpect(jsonPath("$.unitPrice", is(10000)))
                .andExpect(jsonPath("$.itemAmount", is(20000)))
                .andExpect(jsonPath("$.shippingFeeAmount", is(3000)))
                .andExpect(jsonPath("$.remoteAreaFeeAmount", is(2500)))
                .andExpect(jsonPath("$.deductedShippingFeeAmount", is(0)))
                .andExpect(jsonPath("$.refundAmount", is(25500)))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.rejectReason", nullValue()))
                .andExpect(jsonPath("$.failedStep", nullValue()))
                .andExpect(jsonPath("$.requestedAt").exists())
                .andExpect(jsonPath("$.decidedAt").exists())
                .andExpect(jsonPath("$.completedAt").exists());
        assertThat(walletClient.totalAmount()).isEqualTo(25_500L);
    }

    @Test
    void 일부_취소로_무료배송이_깨지면_배송비를_뺀_금액을_돌려주고_뺀_배송비를_주문에_알린다() throws Exception {
        // given: 양말(101) 30,000 + 모자(102) 30,000 = 60,000원, 무료배송(기준 5만 원, 기본 배송비 3,000원). 모자만 취소
        putFreeShippingBundle(30_000L, 30_000L);
        Long refundId = requestedId(HAT_ORDER_ITEM_ID, 1);

        // when
        ResultActions sut = decide(refundId, SELLER_ID, "APPROVE", null);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.deductedShippingFeeAmount", is(3000)))
                .andExpect(jsonPath("$.refundAmount", is(27000)));
        assertSoftly(softly -> {
            softly.assertThat(walletClient.totalAmount()).isEqualTo(27_000L);
            softly.assertThat(orderClient.applied()).containsExactly(
                    new FakeRefundOrderClient.Applied(refundId, HAT_ORDER_ITEM_ID, 1, 3_000L));
        });
    }

    @Test
    void 일부_취소로_무료배송이_깨지는데_취소_금액이_배송비보다_작으면_요청은_409() throws Exception {
        // given: 양말(101) 49,500 + 모자(102) 1,000 = 50,500원, 모자 취소 → 남은 49,500원, 1,000 < 3,000
        putFreeShippingBundle(49_500L, 1_000L);

        // when
        ResultActions sut = request(HAT_ORDER_ITEM_ID, 1);

        // then
        sut.andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/refund/partial-cancel-not-allowed")));
        assertThat(refundRepository.count()).isZero();
    }

    @Test
    void 나눠서_전부_취소하면_돌려준_합이_결제_금액과_같다() throws Exception {
        // given & when: 양말 1개 → 양말 2개 → 모자 1개 차례로 요청·승인
        decide(requestedId(ORDER_ITEM_ID, 1), SELLER_ID, "APPROVE", null).andExpect(status().isOk());
        decide(requestedId(ORDER_ITEM_ID, 2), SELLER_ID, "APPROVE", null).andExpect(status().isOk());
        decide(requestedId(HAT_ORDER_ITEM_ID, 1), SELLER_ID, "APPROVE", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount", is(11000)));

        // then: 10,000 + 20,000 + (5,000 + 3,000 + 3,000) = 결제 41,000원
        assertThat(walletClient.totalAmount()).isEqualTo(41_000L);
    }

    @Test
    void 이미_출고됐으면_승인_불가로_200을_주고_돈을_돌려주지_않는다() throws Exception {
        // given
        Long refundId = requestedId(ORDER_ITEM_ID, 1);
        deliveryClient.markDispatched(ORDER_ITEM_ID);

        // when
        ResultActions sut = decide(refundId, SELLER_ID, "APPROVE", null);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("NOT_APPROVABLE")))
                .andExpect(jsonPath("$.refundAmount", is(0)));
        assertSoftly(softly -> {
            softly.assertThat(walletClient.refunded()).isEmpty();
            softly.assertThat(orderClient.applied()).isEmpty();
            softly.assertThat(productClient.released()).isEmpty();
            softly.assertThat(deliveryClient.confirmedRefundIds()).isEmpty();
            softly.assertThat(refundRepository.findById(refundId).orElseThrow().getActiveOrderItemId()).isNull();
        });
    }

    @Test
    void 배송_건이_아직_없으면_409이고_요청됨으로_남는다() throws Exception {
        // given
        Long refundId = requestedId(ORDER_ITEM_ID, 1);
        deliveryClient.clear();

        // when
        ResultActions sut = decide(refundId, SELLER_ID, "APPROVE", null);

        // then
        sut.andExpect(status().isConflict()).andExpect(jsonPath("$.type", endsWith("/refund/shipment-not-ready")));
        assertThat(refundRepository.findById(refundId).orElseThrow().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 지갑_호출이_실패하면_처리_중으로_남고_실패_단계를_기록한다() throws Exception {
        // given
        Long refundId = requestedId(ORDER_ITEM_ID, 1);
        walletClient.failNext();

        // when
        ResultActions sut = decide(refundId, SELLER_ID, "APPROVE", null);

        // then
        sut.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.type", endsWith("/refund/wallet-api-failed")));
        Refund refund = refundRepository.findById(refundId).orElseThrow();
        assertThat(refund).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(refund.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(refund.getFailedStep()).isEqualTo(RefundFailedStep.WALLET);
            softly.assertThat(refund.getFailureCode()).isEqualTo("WALLET_API_FAILED");
            softly.assertThat(orderClient.applied()).isEmpty();
            softly.assertThat(deliveryClient.confirmedRefundIds()).isEmpty();
        });
    }

    @Test
    void 처리_중으로_멈춘_상품에는_새_요청을_받지_않는다() throws Exception {
        // given
        Long refundId = requestedId(ORDER_ITEM_ID, 1);
        walletClient.failNext();
        decide(refundId, SELLER_ID, "APPROVE", null).andExpect(status().isServiceUnavailable());

        // when & then
        request(ORDER_ITEM_ID, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/refund/refund-already-in-progress")));
    }

    // 판매자 거절·권한

    @Test
    void 거절하면_사유를_남긴다() throws Exception {
        // given
        Long refundId = requestedId(ORDER_ITEM_ID, 1);

        // when
        ResultActions sut = decide(refundId, SELLER_ID, "REJECT", "이미 포장을 마쳤습니다.");

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.rejectReason", is("이미 포장을 마쳤습니다.")))
                .andExpect(jsonPath("$.refundAmount", is(0)));
        assertThat(deliveryClient.reservedRefundIds()).isEmpty();
    }

    @Test
    void 거절_사유가_없으면_422() throws Exception {
        // given
        Long refundId = requestedId(ORDER_ITEM_ID, 1);

        // when & then
        decide(refundId, SELLER_ID, "REJECT", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/refund/reject-reason-required")));
    }

    @Test
    void 다른_판매자의_요청은_처리할_수_없다() throws Exception {
        // given
        Long refundId = requestedId(ORDER_ITEM_ID, 1);

        // when & then
        decide(refundId, OTHER_MEMBER_ID, "APPROVE", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/refund/refund-not-found")));
        assertThat(deliveryClient.reservedRefundIds()).isEmpty();
    }

    @Test
    void 판매자가_아니면_승인_API를_쓸_수_없다() throws Exception {
        // given
        Long refundId = requestedId(ORDER_ITEM_ID, 1);

        // when & then
        mockMvc.perform(patch(SELLER_URL + "/{refundId}", refundId)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"APPROVE\"}"))
                .andExpect(status().isForbidden());
    }

    // 목록

    @Test
    void 구매자는_자기_요청만_최신순으로_본다() throws Exception {
        // given
        Long first = requestedId(ORDER_ITEM_ID, 1);
        Long second = requestedId(HAT_ORDER_ITEM_ID, 1);

        // when
        ResultActions sut = mockMvc.perform(get(BUYER_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")));

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.content[0].refundId", is(second.intValue())))
                .andExpect(jsonPath("$.content[1].refundId", is(first.intValue())));
        mockMvc.perform(get(BUYER_URL).header(HttpHeaders.AUTHORIZATION, tokens.bearer(OTHER_MEMBER_ID, "BUYER")))
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    @Test
    void 판매자는_상태로_걸러_볼_수_있다() throws Exception {
        // given
        Long rejected = requestedId(ORDER_ITEM_ID, 1);
        decide(rejected, SELLER_ID, "REJECT", "품절").andExpect(status().isOk());
        Long requested = requestedId(HAT_ORDER_ITEM_ID, 1);

        // when
        ResultActions sut = mockMvc.perform(get(SELLER_URL).param("status", "REQUESTED")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "SELLER")));

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].refundId", is(requested.intValue())));
    }

    @Test
    void 판매자_목록의_상태_값이_틀리면_422() throws Exception {
        // when & then
        mockMvc.perform(get(SELLER_URL).param("status", "DONE")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "SELLER")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    private ResultActions request(Long orderItemId, int quantity) throws Exception {
        return mockMvc.perform(post(BUYER_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(orderItemId, quantity)));
    }

    // 양말(101)·모자(102) 각 1개, 무료배송이 적용되어 배송비 0원을 낸 묶음(기준 5만 원, 기본 배송비 3,000원)
    private void putFreeShippingBundle(long socksPrice, long hatPrice) {
        List<SellerGroupItem> bundle = List.of(new SellerGroupItem(ORDER_ITEM_ID, socksPrice, 1),
                new SellerGroupItem(HAT_ORDER_ITEM_ID, hatPrice, 1));
        orderClient.put(new RefundableOrderItem(ORDER_ID, ORDER_ITEM_ID, BUYER_ID, SELLER_ID, 55L, "양말", "흰색",
                RefundableOrderItem.CANCELLABLE_STATUS, socksPrice, 1, 0L, 0L, 3_000L, 50_000L, bundle));
        orderClient.put(new RefundableOrderItem(ORDER_ID, HAT_ORDER_ITEM_ID, BUYER_ID, SELLER_ID, 56L, "모자", "검정",
                RefundableOrderItem.CANCELLABLE_STATUS, hatPrice, 1, 0L, 0L, 3_000L, 50_000L, bundle));
    }

    private Long requestedId(Long orderItemId, int quantity) throws Exception {
        request(orderItemId, quantity).andExpect(status().isCreated());
        Long latest = null;
        for (Refund refund : refundRepository.findAll()) {
            if (refund.getOrderItemId().equals(orderItemId) && refund.getStatus() == RefundStatus.REQUESTED) {
                latest = refund.getId();
            }
        }
        return latest;
    }

    private ResultActions decide(Long refundId, Long sellerId, String decision, String rejectReason) throws Exception {
        String reason = rejectReason == null ? "null" : "\"" + rejectReason + "\"";
        return mockMvc.perform(patch(SELLER_URL + "/{refundId}", refundId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(sellerId, "SELLER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\": \"" + decision + "\", \"rejectReason\": " + reason + "}"));
    }

    private Refund onlyRefund() {
        List<Refund> all = refundRepository.findAll();
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private static String body(Long orderItemId, int quantity) {
        return "{\"orderId\": " + ORDER_ID + ", \"orderItemId\": " + orderItemId + ", \"quantity\": " + quantity + "}";
    }
}
