package com.ok.delivery;

import com.ok.delivery.adapter.out.persistence.ShipmentJpaRepository;
import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentStatus;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeCarrierClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.ok.delivery.fixture.ShipmentFixture.shipmentIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 택배사가 인증 헤더 없이 호출하는 알림 API를 컨트롤러부터 DB까지 확인한다. 택배사 조회는 FakeCarrierClient가 대신한다.
// 택배사 식별자 매핑은 TrackerCarrierClientWebhookTest, 분기별 처리는 CarrierNotificationFacadeTest가 맡는다.
class CarrierNotificationApiIntegrationTest extends AbstractIntegrationTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String TRACKING_NUMBER = "1234567890";

    @Autowired
    WebApplicationContext context;

    @Autowired
    ShipmentJpaRepository shipmentRepository;

    @Autowired
    FakeCarrierClient carrierClient;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        carrierClient.reset();
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    @Test
    void 택배사가_배송_완료로_답하면_배송_중인_shipment가_배송_완료가_된다() throws Exception {
        // given
        Long shipmentId = saveShippingShipment();
        carrierClient.markDelivered(TRACKING_NUMBER);

        // when
        ResultActions result = notify(SECRET, "CJ_LOGISTICS", TRACKING_NUMBER);

        // then
        result.andExpect(status().isNoContent());
        Shipment sut = shipmentRepository.findById(shipmentId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
            softly.assertThat(sut.getDeliveredAt()).isNotNull();
        });
    }

    @Test
    void 같은_알림이_반복돼도_모두_2XX이고_결과가_같다() throws Exception {
        // given
        Long shipmentId = saveShippingShipment();
        carrierClient.markDelivered(TRACKING_NUMBER);

        // when
        notify(SECRET, "CJ_LOGISTICS", TRACKING_NUMBER).andExpect(status().isNoContent());
        var deliveredAt = shipmentRepository.findById(shipmentId).orElseThrow().getDeliveredAt();
        ResultActions result = notify(SECRET, "CJ_LOGISTICS", TRACKING_NUMBER);

        // then
        result.andExpect(status().isNoContent());
        Shipment sut = shipmentRepository.findById(shipmentId).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
            softly.assertThat(sut.getDeliveredAt()).isEqualTo(deliveredAt);
        });
    }

    @Test
    void 아직_배송_완료가_아니면_2XX로_답하고_상태를_바꾸지_않는다() throws Exception {
        // given
        Long shipmentId = saveShippingShipment();

        // when
        ResultActions result = notify(SECRET, "CJ_LOGISTICS", TRACKING_NUMBER);

        // then
        result.andExpect(status().isNoContent());
        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.SHIPPING);
    }

    @Test
    void 모르는_송장이나_택배사_식별자도_2XX로_답한다() throws Exception {
        // when & then
        notify(SECRET, "CJ_LOGISTICS", "9999999999").andExpect(status().isNoContent());
        notify(SECRET, "UNKNOWN", TRACKING_NUMBER).andExpect(status().isNoContent());
        notify(SECRET, null, null).andExpect(status().isNoContent());
    }

    @Test
    void 비밀값이_틀리면_거절하고_상태를_바꾸지_않는다() throws Exception {
        // given
        Long shipmentId = saveShippingShipment();
        carrierClient.markDelivered(TRACKING_NUMBER);

        // when & then
        notify("wrong-secret", "CJ_LOGISTICS", TRACKING_NUMBER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type", endsWith("/delivery/invalid-carrier-notification-secret")));
        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.SHIPPING);
    }

    @Test
    void 인증_헤더가_없어도_비밀값만_맞으면_호출할_수_있다() throws Exception {
        // when & then
        mockMvc.perform(post("/api/v1/carrier-notifications/" + SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierId\":\"CJ_LOGISTICS\",\"trackingNumber\":\"" + TRACKING_NUMBER + "\"}"))
                .andExpect(status().isNoContent());
    }

    private Long saveShippingShipment() {
        return shipmentRepository.save(shipmentIn(ShipmentStatus.SHIPPING)).getId();
    }

    private ResultActions notify(String secret, String carrierId, String trackingNumber) throws Exception {
        String body = carrierId == null
                ? "{}"
                : "{\"carrierId\":\"" + carrierId + "\",\"trackingNumber\":\"" + trackingNumber + "\"}";
        return mockMvc.perform(post("/api/v1/carrier-notifications/" + secret)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
