package com.ok.delivery;

import com.ok.delivery.adapter.out.persistence.ShipmentJpaRepository;
import com.ok.delivery.application.dto.AddressChangeDecision;
import com.ok.delivery.application.dto.AddressChangeDecisionCommand;
import com.ok.delivery.application.service.ShipmentAddressChangeService;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import com.ok.delivery.domain.ShipmentItem;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.ok.delivery.fixture.ShipmentFixture.BUYER_ID;
import static com.ok.delivery.fixture.ShipmentFixture.addressChangeCommand;
import static com.ok.delivery.fixture.ShipmentFixture.item;
import static com.ok.delivery.fixture.ShipmentFixture.shipment;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 구매자 배송 목록·상세 API를 호출해 본인 확인, 페이지, 상세 응답, 배송지 변경 요청 상태별 응답을 확인한다.
// 배송지 변경 요청과 승인·거절은 서비스로 만든다. 그 API 자체는 ShipmentAddressChangeApiIntegrationTest가 맡는다.
// 추가 운임 기대값은 마이그레이션 시드(63100 JEJU_MAIN 3,000원)에 의존한다. 시드가 바뀌면 함께 바꾼다.
class ShipmentApiIntegrationTest extends AbstractIntegrationTest {

    private static final Long SELLER_ID = 10L;
    private static final Long OTHER_BUYER_ID = 999L;

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    ShipmentJpaRepository shipmentRepository;

    @Autowired
    ShipmentAddressChangeService addressChangeService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 보안 필터를 적용해야 토큰 검증과 401·403 처리가 실제와 같게 동작한다
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM shipment_address_change_request");
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    @Test
    void 목록은_본인_배송만_최신순으로_대표_상품과_나머지_수를_돌려준다() throws Exception {
        // given
        Long olderId = saveShipment(1L, item(1L));
        Long newerId = saveShipment(2L,
                ShipmentItem.create(2L, "무선 마우스", null, 1, 20_000),
                ShipmentItem.create(3L, "마우스 패드", null, 1, 5_000),
                ShipmentItem.create(4L, "USB 허브", null, 1, 15_000));
        Long otherBuyersId = saveShipment(3L, item(5L));
        jdbcTemplate.update("UPDATE shipment SET buyer_id = ? WHERE id = ?", OTHER_BUYER_ID, otherBuyersId);

        // when
        ResultActions sut = getList(BUYER_ID, "0");

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].shipmentId", is(newerId.intValue())))
                .andExpect(jsonPath("$.content[0].orderId", is(2)))
                .andExpect(jsonPath("$.content[0].status", is("PREPARING")))
                .andExpect(jsonPath("$.content[0].carrier", nullValue()))
                .andExpect(jsonPath("$.content[0].trackingNumber", nullValue()))
                .andExpect(jsonPath("$.content[0].estimatedDeliveryStart", is("2026-10-13")))
                .andExpect(jsonPath("$.content[0].estimatedDeliveryEnd", is("2026-10-14")))
                .andExpect(jsonPath("$.content[0].delayed", is(false)))
                .andExpect(jsonPath("$.content[0].createdAt", notNullValue()))
                .andExpect(jsonPath("$.content[0].representativeProductName", is("무선 마우스")))
                .andExpect(jsonPath("$.content[0].otherItemCount", is(2)))
                .andExpect(jsonPath("$.content[1].shipmentId", is(olderId.intValue())))
                .andExpect(jsonPath("$.content[1].otherItemCount", is(0)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(25)))
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.totalPages", is(1)));
    }

    @Test
    void 목록은_25건씩_나뉜다() throws Exception {
        // given
        for (long orderId = 1; orderId <= 26; orderId++) {
            saveShipment(orderId, item(orderId));
        }

        // when & then
        getList(BUYER_ID, "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(25)))
                .andExpect(jsonPath("$.totalElements", is(26)))
                .andExpect(jsonPath("$.totalPages", is(2)));
        getList(BUYER_ID, "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].orderId", is(1)));
    }

    @Test
    void 페이지_번호가_음수면_422_VALIDATION_FAILED() throws Exception {
        // when & then
        getList(BUYER_ID, "-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    @Test
    void 상세는_배송_정보와_상품_항목을_돌려주고_변경_요청이_없으면_null과_0이다() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, item(1L));

        // when
        ResultActions sut = getDetail(BUYER_ID, shipmentId);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentId", is(shipmentId.intValue())))
                .andExpect(jsonPath("$.orderId", is(1)))
                .andExpect(jsonPath("$.status", is("PREPARING")))
                .andExpect(jsonPath("$.carrier", nullValue()))
                .andExpect(jsonPath("$.trackingNumber", nullValue()))
                .andExpect(jsonPath("$.address.recipientName", is("홍길동")))
                .andExpect(jsonPath("$.address.recipientPhone", is("01012345678")))
                .andExpect(jsonPath("$.address.postalCode", is("06236")))
                .andExpect(jsonPath("$.address.address", is("서울시 강남구 테헤란로 1")))
                .andExpect(jsonPath("$.address.addressDetail", is("101동 202호")))
                .andExpect(jsonPath("$.address.deliveryNote", is("문 앞에 놓아주세요")))
                .andExpect(jsonPath("$.regionGroup", is("GENERAL")))
                .andExpect(jsonPath("$.chargedShippingFee", is(3_000)))
                .andExpect(jsonPath("$.additionalShippingFee", is(0)))
                .andExpect(jsonPath("$.estimatedShipDate", is("2026-10-11")))
                .andExpect(jsonPath("$.estimatedDeliveryStart", is("2026-10-13")))
                .andExpect(jsonPath("$.estimatedDeliveryEnd", is("2026-10-14")))
                .andExpect(jsonPath("$.delayed", is(false)))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.shippedAt", nullValue()))
                .andExpect(jsonPath("$.deliveredAt", nullValue()))
                .andExpect(jsonPath("$.completedAt", nullValue()))
                .andExpect(jsonPath("$.cancelledAt", nullValue()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].orderItemId", is(1)))
                .andExpect(jsonPath("$.items[0].productName", is("무선 키보드")))
                .andExpect(jsonPath("$.items[0].optionName", is("블랙")))
                .andExpect(jsonPath("$.items[0].quantity", is(2)))
                .andExpect(jsonPath("$.items[0].unitPrice", is(30_000)))
                .andExpect(jsonPath("$.items[0].canceledQuantity", is(0)))
                .andExpect(jsonPath("$.items[0].confirmed", is(false)))
                .andExpect(jsonPath("$.latestAddressChange", nullValue()));
    }

    @Test
    void 변경_요청이_대기_중이면_최근_요청을_돌려주고_추가_배송비는_0이다() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, item(1L));
        Long requestId = addressChangeService.request(addressChangeCommand(shipmentId)).requestId();

        // when & then
        getDetail(BUYER_ID, shipmentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestAddressChange.requestId", is(requestId.intValue())))
                .andExpect(jsonPath("$.latestAddressChange.status", is("PENDING")))
                .andExpect(jsonPath("$.latestAddressChange.requestedAddress.postalCode", is("63100")))
                .andExpect(jsonPath("$.latestAddressChange.additionalFeeNotice", is(3_000)))
                .andExpect(jsonPath("$.latestAddressChange.rejectReason", nullValue()))
                .andExpect(jsonPath("$.latestAddressChange.requestedAt", notNullValue()))
                .andExpect(jsonPath("$.latestAddressChange.processedAt", nullValue()))
                .andExpect(jsonPath("$.additionalShippingFee", is(0)))
                .andExpect(jsonPath("$.address.postalCode", is("06236")));
    }

    @Test
    void 변경_요청이_승인되면_바뀐_배송지와_추가_배송비를_돌려준다() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, item(1L));
        Long requestId = addressChangeService.request(addressChangeCommand(shipmentId)).requestId();
        addressChangeService.decide(new AddressChangeDecisionCommand(shipmentId, requestId, SELLER_ID, AddressChangeDecision.APPROVE));

        // when & then
        getDetail(BUYER_ID, shipmentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestAddressChange.status", is("APPROVED")))
                .andExpect(jsonPath("$.latestAddressChange.processedAt", notNullValue()))
                .andExpect(jsonPath("$.additionalShippingFee", is(3_000)))
                .andExpect(jsonPath("$.address.postalCode", is("63100")))
                .andExpect(jsonPath("$.regionGroup", is("JEJU_MAIN")))
                .andExpect(jsonPath("$.chargedShippingFee", is(3_000)));
    }

    @Test
    void 변경_요청이_거절되면_거절_사유를_돌려주고_추가_배송비는_0이다() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, item(1L));
        Long requestId = addressChangeService.request(addressChangeCommand(shipmentId)).requestId();
        addressChangeService.decide(new AddressChangeDecisionCommand(shipmentId, requestId, SELLER_ID, AddressChangeDecision.REJECT));

        // when & then
        getDetail(BUYER_ID, shipmentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestAddressChange.status", is("REJECTED")))
                .andExpect(jsonPath("$.latestAddressChange.rejectReason", is(ShipmentAddressChangeRequest.REJECT_REASON)))
                .andExpect(jsonPath("$.additionalShippingFee", is(0)))
                .andExpect(jsonPath("$.address.postalCode", is("06236")));
    }

    @Test
    void 다른_구매자의_shipment면_404() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, item(1L));

        // when & then
        getDetail(OTHER_BUYER_ID, shipmentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/delivery/shipment-not-found")));
    }

    @Test
    void 없는_shipment면_404() throws Exception {
        // when & then
        getDetail(BUYER_ID, Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/delivery/shipment-not-found")));
    }

    @Test
    void 일반_회원_권한이_없으면_403() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/shipments")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID)))
                .andExpect(status().isForbidden());
    }

    private Long saveShipment(long orderId, ShipmentItem... items) {
        return shipmentRepository.save(shipment(orderId, SELLER_ID, items)).getId();
    }

    private ResultActions getList(Long buyerId, String page) throws Exception {
        return mockMvc.perform(get("/api/v1/shipments")
                .param("page", page)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(buyerId, "BUYER")));
    }

    private ResultActions getDetail(Long buyerId, Long shipmentId) throws Exception {
        return mockMvc.perform(get("/api/v1/shipments/" + shipmentId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(buyerId, "BUYER")));
    }
}
