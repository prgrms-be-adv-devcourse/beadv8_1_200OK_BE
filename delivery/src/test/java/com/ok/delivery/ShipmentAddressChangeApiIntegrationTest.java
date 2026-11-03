package com.ok.delivery;

import com.jayway.jsonpath.JsonPath;
import com.ok.delivery.adapter.out.persistence.ShipmentJpaRepository;
import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static com.ok.delivery.fixture.ShipmentFixture.BUYER_ID;
import static com.ok.delivery.fixture.ShipmentFixture.item;
import static com.ok.delivery.fixture.ShipmentFixture.shipment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 구매자의 배송지 변경 요청과 판매자의 승인·거절 API를 호출해 권한, 상태 변경, 오류 응답을 확인한다.
// 추가 운임 기대값은 마이그레이션 시드(63100 JEJU_MAIN 3,000원, 63000 JEJU_ISLANDS 5,000원)에 의존한다. 시드가 바뀌면 함께 바꾼다.
// 차액 계산 경우와 도착 예정일 계산은 ShipmentTest, ShipmentAddressChangeServiceTest가, 동시 요청은 ShipmentAddressChangeServiceConcurrencyIntegrationTest가 맡는다.
class ShipmentAddressChangeApiIntegrationTest extends AbstractIntegrationTest {

    private static final Long SELLER_ID = 10L;
    private static final Long OTHER_MEMBER_ID = 999L;
    private static final String JEJU_BODY = addressBody("63100");
    private static final String APPROVE_BODY = """
            {"decision": "APPROVE"}
            """;
    private static final String REJECT_BODY = """
            {"decision": "REJECT"}
            """;

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    ShipmentJpaRepository shipmentRepository;

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
    void 구매자가_배송지_변경을_요청하면_201과_추가_운임_안내_금액을_돌려준다() throws Exception {
        // given
        Long shipmentId = saveShipment();

        // when
        ResultActions sut = request(BUYER_ID, shipmentId, JEJU_BODY);

        // then
        sut.andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId", notNullValue()))
                .andExpect(jsonPath("$.shipmentId", is(shipmentId.intValue())))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.additionalFeeNotice", is(3_000)))
                .andExpect(jsonPath("$.rejectReason", nullValue()))
                .andExpect(jsonPath("$.requestedAt", notNullValue()))
                .andExpect(jsonPath("$.processedAt", nullValue()));
    }

    @Test
    void 다른_구매자의_shipment면_404() throws Exception {
        // given
        Long shipmentId = saveShipment();

        // when & then
        request(OTHER_MEMBER_ID, shipmentId, JEJU_BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/delivery/shipment-not-found")));
        assertThat(countRequests(shipmentId)).isZero();
    }

    @Test
    void 대기_중인_요청이_있으면_409_ADDRESS_CHANGE_ALREADY_PENDING() throws Exception {
        // given
        Long shipmentId = saveShipment();
        request(BUYER_ID, shipmentId, JEJU_BODY).andExpect(status().isCreated());

        // when & then
        request(BUYER_ID, shipmentId, JEJU_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/delivery/address-change-already-pending")));
    }

    @Test
    void 송장_발행_후에는_409_ADDRESS_CHANGE_NOT_ALLOWED() throws Exception {
        // given
        Long shipmentId = saveShipment();
        updateStatus(shipmentId, "SHIPPING");

        // when & then
        request(BUYER_ID, shipmentId, JEJU_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/delivery/address-change-not-allowed")));
    }

    @Test
    void 우편번호가_숫자_5자리가_아니면_422_VALIDATION_FAILED() throws Exception {
        // given
        Long shipmentId = saveShipment();

        // when & then
        request(BUYER_ID, shipmentId, addressBody("1234"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    @Test
    void 일반_회원_권한이_없으면_403() throws Exception {
        // given
        Long shipmentId = saveShipment();

        // when & then
        mockMvc.perform(post(requestUrl(shipmentId))
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JEJU_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void 판매자가_승인하면_200이고_shipment의_배송지_지역_추가_운임_기준이_바뀐다() throws Exception {
        // given
        Long shipmentId = saveShipment();
        Long requestId = requestIdOf(request(BUYER_ID, shipmentId, JEJU_BODY));

        // when
        ResultActions sut = decide(SELLER_ID, shipmentId, requestId, APPROVE_BODY);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId", is(requestId.intValue())))
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.processedAt", notNullValue()));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT postal_code, region_group, region_surcharge, charged_shipping_fee FROM shipment WHERE id = ?", shipmentId);
        assertSoftly(softly -> {
            softly.assertThat(row.get("postal_code")).isEqualTo("63100");
            softly.assertThat(row.get("region_group")).isEqualTo("JEJU_MAIN");
            softly.assertThat(row.get("region_surcharge")).isEqualTo(3_000);
            softly.assertThat(row.get("charged_shipping_fee")).isEqualTo(3_000);
        });
    }

    @Test
    void 승인된_변경_뒤에_다시_요청하면_차액만_안내한다() throws Exception {
        // given
        Long shipmentId = saveShipment();
        Long firstId = requestIdOf(request(BUYER_ID, shipmentId, JEJU_BODY));
        decide(SELLER_ID, shipmentId, firstId, APPROVE_BODY).andExpect(status().isOk());

        // when
        ResultActions sut = request(BUYER_ID, shipmentId, addressBody("63000"));

        // then
        sut.andExpect(status().isCreated())
                .andExpect(jsonPath("$.additionalFeeNotice", is(2_000)));
    }

    @Test
    void 판매자가_거절하면_200과_고정_사유를_남기고_배송지는_그대로다() throws Exception {
        // given
        Long shipmentId = saveShipment();
        Long requestId = requestIdOf(request(BUYER_ID, shipmentId, JEJU_BODY));

        // when
        ResultActions sut = decide(SELLER_ID, shipmentId, requestId, REJECT_BODY);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.rejectReason", is(ShipmentAddressChangeRequest.REJECT_REASON)));
        assertThat(jdbcTemplate.queryForObject("SELECT postal_code FROM shipment WHERE id = ?", String.class, shipmentId))
                .isEqualTo("06236");
    }

    @Test
    void 다른_판매자의_shipment면_404_SHIPMENT_NOT_FOUND() throws Exception {
        // given
        Long shipmentId = saveShipment();
        Long requestId = requestIdOf(request(BUYER_ID, shipmentId, JEJU_BODY));

        // when & then
        decide(OTHER_MEMBER_ID, shipmentId, requestId, APPROVE_BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/delivery/shipment-not-found")));
    }

    @Test
    void 다른_shipment의_요청이면_404_ADDRESS_CHANGE_REQUEST_NOT_FOUND() throws Exception {
        // given
        Long shipmentId = saveShipment();
        Long otherShipmentId = shipmentRepository.save(shipment(2L, SELLER_ID, item(2L))).getId();
        Long otherRequestId = requestIdOf(request(BUYER_ID, otherShipmentId, JEJU_BODY));

        // when & then
        decide(SELLER_ID, shipmentId, otherRequestId, APPROVE_BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/delivery/address-change-request-not-found")));
    }

    @Test
    void 이미_처리된_요청이면_409_ADDRESS_CHANGE_ALREADY_PROCESSED() throws Exception {
        // given
        Long shipmentId = saveShipment();
        Long requestId = requestIdOf(request(BUYER_ID, shipmentId, JEJU_BODY));
        decide(SELLER_ID, shipmentId, requestId, REJECT_BODY).andExpect(status().isOk());

        // when & then
        decide(SELLER_ID, shipmentId, requestId, APPROVE_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/delivery/address-change-already-processed")));
    }

    @Test
    void 취소된_shipment의_요청을_승인하면_409_INVALID_SHIPMENT_STATE이고_요청은_대기_상태로_남는다() throws Exception {
        // given
        Long shipmentId = saveShipment();
        Long requestId = requestIdOf(request(BUYER_ID, shipmentId, JEJU_BODY));
        updateStatus(shipmentId, "CANCELLED");

        // when & then
        decide(SELLER_ID, shipmentId, requestId, APPROVE_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/delivery/invalid-shipment-state")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM shipment_address_change_request WHERE id = ?", String.class, requestId))
                .isEqualTo("PENDING");
    }

    @Test
    void 처리_결정이_허용_값이_아니면_422_VALIDATION_FAILED() throws Exception {
        // given
        Long shipmentId = saveShipment();
        Long requestId = requestIdOf(request(BUYER_ID, shipmentId, JEJU_BODY));

        // when & then
        decide(SELLER_ID, shipmentId, requestId, """
                {"decision": "HOLD"}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    private Long saveShipment() {
        return shipmentRepository.save(shipment(1L, SELLER_ID, item(1L))).getId();
    }

    private void updateStatus(Long shipmentId, String status) {
        jdbcTemplate.update("UPDATE shipment SET status = ? WHERE id = ?", status, shipmentId);
    }

    private Integer countRequests(Long shipmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shipment_address_change_request WHERE shipment_id = ?", Integer.class, shipmentId);
    }

    private ResultActions request(Long buyerId, Long shipmentId, String body) throws Exception {
        return mockMvc.perform(post(requestUrl(shipmentId))
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(buyerId, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions decide(Long sellerId, Long shipmentId, Long requestId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/seller/shipments/" + shipmentId + "/address-change-requests/" + requestId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(sellerId, "BUYER", "SELLER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static Long requestIdOf(ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.requestId")).longValue();
    }

    private static String requestUrl(Long shipmentId) {
        return "/api/v1/shipments/" + shipmentId + "/address-change-requests";
    }

    private static String addressBody(String postalCode) {
        return """
                {"recipientName": "김철수", "recipientPhone": "010-1234-5678", "postalCode": "%s",
                 "address": "제주특별자치도 제주시 첨단로 1", "addressDetail": "2층"}
                """.formatted(postalCode);
    }
}
