package com.ok.delivery;

import com.ok.delivery.domain.ShipmentItem;
import com.ok.delivery.repository.ShipmentRepository;
import com.ok.delivery.service.ShipmentAddressChangeService;
import com.ok.delivery.service.dto.AddressChangeDecision;
import com.ok.delivery.service.dto.AddressChangeDecisionCommand;
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

// 토큰으로 판매자 출고 목록·상세 API를 호출해 본인 확인, 목록 항목, 대기 중인 배송지 변경 요청 표시를 확인한다.
// 상세 응답의 전체 필드는 구매자 상세와 같아 ShipmentApiIntegrationTest가 맡는다.
class SellerShipmentQueryApiIntegrationTest extends AbstractIntegrationTest {

    private static final Long SELLER_ID = 10L;
    private static final Long OTHER_SELLER_ID = 20L;

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    ShipmentRepository shipmentRepository;

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
    void 목록은_본인_배송만_최신순으로_수령인_지역_출고_예정일_대표_상품을_돌려준다() throws Exception {
        // given
        Long olderId = saveShipment(1L, SELLER_ID, item(1L));
        Long newerId = saveShipment(2L, SELLER_ID,
                ShipmentItem.create(2L, "무선 마우스", null, 1, 20_000),
                ShipmentItem.create(3L, "마우스 패드", null, 1, 5_000));
        saveShipment(3L, OTHER_SELLER_ID, item(4L));

        // when
        ResultActions sut = getList(SELLER_ID, "0");

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].shipmentId", is(newerId.intValue())))
                .andExpect(jsonPath("$.content[0].orderId", is(2)))
                .andExpect(jsonPath("$.content[0].status", is("PREPARING")))
                .andExpect(jsonPath("$.content[0].recipientName", is("홍길동")))
                .andExpect(jsonPath("$.content[0].regionGroup", is("GENERAL")))
                .andExpect(jsonPath("$.content[0].estimatedShipDate", is("2026-10-11")))
                .andExpect(jsonPath("$.content[0].carrier", nullValue()))
                .andExpect(jsonPath("$.content[0].trackingNumber", nullValue()))
                .andExpect(jsonPath("$.content[0].delayed", is(false)))
                .andExpect(jsonPath("$.content[0].createdAt", notNullValue()))
                .andExpect(jsonPath("$.content[0].representativeProductName", is("무선 마우스")))
                .andExpect(jsonPath("$.content[0].otherItemCount", is(1)))
                .andExpect(jsonPath("$.content[0].addressChangePending", is(false)))
                .andExpect(jsonPath("$.content[1].shipmentId", is(olderId.intValue())))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(25)))
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.totalPages", is(1)));
    }

    @Test
    void 대기_중인_배송지_변경_요청이_있는_배송만_표시한다() throws Exception {
        // given
        Long pendingId = saveShipment(1L, SELLER_ID, item(1L));
        Long approvedId = saveShipment(2L, SELLER_ID, item(2L));
        addressChangeService.request(addressChangeCommand(pendingId));
        Long requestId = addressChangeService.request(addressChangeCommand(approvedId)).requestId();
        addressChangeService.decide(new AddressChangeDecisionCommand(approvedId, requestId, SELLER_ID, AddressChangeDecision.APPROVE));

        // when & then: 최신순이라 승인된 배송(orderId 2)이 먼저다
        getList(SELLER_ID, "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].shipmentId", is(approvedId.intValue())))
                .andExpect(jsonPath("$.content[0].addressChangePending", is(false)))
                .andExpect(jsonPath("$.content[0].regionGroup", is("JEJU_MAIN")))
                .andExpect(jsonPath("$.content[0].recipientName", is("김철수")))
                .andExpect(jsonPath("$.content[1].shipmentId", is(pendingId.intValue())))
                .andExpect(jsonPath("$.content[1].addressChangePending", is(true)));
    }

    @Test
    void 페이지_번호가_음수면_422_VALIDATION_FAILED() throws Exception {
        // when & then
        getList(SELLER_ID, "-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    @Test
    void 상세는_대기_중인_배송지_변경_요청과_출고_예정일을_돌려준다() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, SELLER_ID, item(1L));
        Long requestId = addressChangeService.request(addressChangeCommand(shipmentId)).requestId();

        // when & then
        getDetail(SELLER_ID, shipmentId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentId", is(shipmentId.intValue())))
                .andExpect(jsonPath("$.estimatedShipDate", is("2026-10-11")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.latestAddressChange.requestId", is(requestId.intValue())))
                .andExpect(jsonPath("$.latestAddressChange.status", is("PENDING")));
    }

    @Test
    void 다른_판매자의_shipment면_404() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, OTHER_SELLER_ID, item(1L));

        // when & then
        getDetail(SELLER_ID, shipmentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/delivery/shipment-not-found")));
    }

    @Test
    void 없는_shipment면_404() throws Exception {
        // when & then
        getDetail(SELLER_ID, Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/delivery/shipment-not-found")));
    }

    @Test
    void 판매자가_아니면_403() throws Exception {
        // when & then
        mockMvc.perform(get("/api/v1/seller/shipments")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")))
                .andExpect(status().isForbidden());
    }

    private Long saveShipment(long orderId, long sellerId, ShipmentItem... items) {
        return shipmentRepository.save(shipment(orderId, sellerId, items)).getId();
    }

    private ResultActions getList(Long sellerId, String page) throws Exception {
        return mockMvc.perform(get("/api/v1/seller/shipments")
                .param("page", page)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(sellerId, "BUYER", "SELLER")));
    }

    private ResultActions getDetail(Long sellerId, Long shipmentId) throws Exception {
        return mockMvc.perform(get("/api/v1/seller/shipments/" + shipmentId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(sellerId, "BUYER", "SELLER")));
    }
}
