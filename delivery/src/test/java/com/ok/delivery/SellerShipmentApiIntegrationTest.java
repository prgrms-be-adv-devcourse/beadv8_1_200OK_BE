package com.ok.delivery;

import com.ok.delivery.domain.ShipmentAddressChangeRequest;
import com.ok.delivery.repository.ShipmentRepository;
import com.ok.delivery.service.ShipmentAddressChangeService;
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

import static com.ok.delivery.fixture.ShipmentFixture.addressChangeCommand;
import static com.ok.delivery.fixture.ShipmentFixture.item;
import static com.ok.delivery.fixture.ShipmentFixture.shipment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 판매자 출고 API를 호출해 권한, 요청 검증, 상태 변경, 배송지 변경 요청 자동 거절, 오류 응답을 확인한다.
// 송장번호 형식·취소 예약의 세부 규칙은 ShipmentTest가, 취소 예약과의 경합은 ShipmentDispatchConcurrencyIntegrationTest가 맡는다.
class SellerShipmentApiIntegrationTest extends AbstractIntegrationTest {

    private static final Long SELLER_ID = 10L;
    private static final Long OTHER_SELLER_ID = 20L;
    private static final String DISPATCH_BODY = """
            {"carrier": "CJ_LOGISTICS", "trackingNumber": "1234567890"}
            """;

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
    void 판매자가_송장을_등록하면_200과_출고_정보를_돌려주고_배송중이_된다() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, SELLER_ID, 1L);

        // when
        ResultActions sut = dispatch(SELLER_ID, shipmentId, DISPATCH_BODY);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentId", is(shipmentId.intValue())))
                .andExpect(jsonPath("$.status", is("SHIPPING")))
                .andExpect(jsonPath("$.carrier", is("CJ_LOGISTICS")))
                .andExpect(jsonPath("$.trackingNumber", is("1234567890")))
                .andExpect(jsonPath("$.shippedAt", notNullValue()));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, carrier, tracking_number, shipped_at FROM shipment WHERE id = ?", shipmentId);
        assertSoftly(softly -> {
            softly.assertThat(row.get("status")).isEqualTo("SHIPPING");
            softly.assertThat(row.get("carrier")).isEqualTo("CJ_LOGISTICS");
            softly.assertThat(row.get("tracking_number")).isEqualTo("1234567890");
            softly.assertThat(row.get("shipped_at")).isNotNull();
        });
    }

    @Test
    void 송장을_등록하면_대기_중인_배송지_변경_요청이_고정_문구로_거절된다() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, SELLER_ID, 1L);
        Long requestId = addressChangeService.request(addressChangeCommand(shipmentId)).requestId();

        // when
        dispatch(SELLER_ID, shipmentId, DISPATCH_BODY).andExpect(status().isOk());

        // then
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, reject_reason, processed_at FROM shipment_address_change_request WHERE id = ?", requestId);
        assertSoftly(softly -> {
            softly.assertThat(row.get("status")).isEqualTo("REJECTED");
            softly.assertThat(row.get("reject_reason")).isEqualTo(ShipmentAddressChangeRequest.REJECT_REASON);
            softly.assertThat(row.get("processed_at")).isNotNull();
        });
    }

    @Test
    void 다른_판매자의_shipment면_404() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, OTHER_SELLER_ID, 1L);

        // when & then
        dispatch(SELLER_ID, shipmentId, DISPATCH_BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/delivery/shipment-not-found")));
        assertThat(statusOf(shipmentId)).isEqualTo("PREPARING");
    }

    @Test
    void 없는_shipment면_404() throws Exception {
        // when & then
        dispatch(SELLER_ID, Long.MAX_VALUE, DISPATCH_BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/delivery/shipment-not-found")));
    }

    @Test
    void 이미_출고한_shipment면_409_INVALID_SHIPMENT_STATE() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, SELLER_ID, 1L);
        dispatch(SELLER_ID, shipmentId, DISPATCH_BODY).andExpect(status().isOk());

        // when & then
        dispatch(SELLER_ID, shipmentId, """
                {"carrier": "HANJIN", "trackingNumber": "9876543210"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/delivery/invalid-shipment-state")));
    }

    @Test
    void 같은_택배사의_송장번호가_이미_쓰였으면_409_TRACKING_NUMBER_DUPLICATED() throws Exception {
        // given
        Long firstId = saveShipment(1L, SELLER_ID, 1L);
        Long secondId = saveShipment(2L, SELLER_ID, 2L);
        dispatch(SELLER_ID, firstId, DISPATCH_BODY).andExpect(status().isOk());

        // when & then
        dispatch(SELLER_ID, secondId, DISPATCH_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/delivery/tracking-number-duplicated")));
        assertThat(statusOf(secondId)).isEqualTo("PREPARING");
    }

    @Test
    void 취소_확정_예약된_항목이_있으면_409_CANCELLATION_RESERVED() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, SELLER_ID, 1L);
        jdbcTemplate.update("UPDATE shipment_item SET reserved_cancel_quantity = 1 WHERE shipment_id = ?", shipmentId);

        // when & then
        dispatch(SELLER_ID, shipmentId, DISPATCH_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/delivery/cancellation-reserved")));
        assertThat(statusOf(shipmentId)).isEqualTo("PREPARING");
    }

    @Test
    void 택배사가_허용_값이_아니면_422_VALIDATION_FAILED() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, SELLER_ID, 1L);

        // when & then
        dispatch(SELLER_ID, shipmentId, """
                {"carrier": "FEDEX", "trackingNumber": "1234567890"}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    @Test
    void 송장번호가_숫자_10자리에서_13자리가_아니면_422_INVALID_TRACKING_NUMBER() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, SELLER_ID, 1L);

        // when & then
        dispatch(SELLER_ID, shipmentId, """
                {"carrier": "CJ_LOGISTICS", "trackingNumber": "123456789"}
                """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/delivery/invalid-tracking-number")));
        assertThat(statusOf(shipmentId)).isEqualTo("PREPARING");
    }

    @Test
    void 판매자가_아니면_403() throws Exception {
        // given
        Long shipmentId = saveShipment(1L, SELLER_ID, 1L);

        // when & then
        mockMvc.perform(post(url(shipmentId))
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "BUYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DISPATCH_BODY))
                .andExpect(status().isForbidden());
    }

    private Long saveShipment(long orderId, long sellerId, long orderItemId) {
        return shipmentRepository.save(shipment(orderId, sellerId, item(orderItemId))).getId();
    }

    private String statusOf(Long shipmentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM shipment WHERE id = ?", String.class, shipmentId);
    }

    private ResultActions dispatch(Long sellerId, Long shipmentId, String body) throws Exception {
        return mockMvc.perform(post(url(shipmentId))
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(sellerId, "BUYER", "SELLER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String url(Long shipmentId) {
        return "/api/v1/seller/shipments/" + shipmentId + "/dispatch";
    }
}
