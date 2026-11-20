package com.ok.delivery;

import com.ok.delivery.adapter.out.persistence.ShipmentJpaRepository;
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

import static com.ok.delivery.fixture.ShipmentFixture.BUYER_ID;
import static com.ok.delivery.fixture.ShipmentFixture.item;
import static com.ok.delivery.fixture.ShipmentFixture.shipment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 기본 배송지 조회·등록·수정 API를 호출해 권한, 저장 결과, 오류 응답을 확인한다.
class DefaultShippingAddressApiIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/shipments/addresses/default";
    private static final Long OTHER_MEMBER_ID = 999L;
    private static final String SEOUL_BODY = """
            {"recipientName": "홍길동", "recipientPhone": "010-1234-5678", "postalCode": "06236",
             "address": "서울시 강남구 테헤란로 1", "addressDetail": "101동 202호", "deliveryNote": "문 앞에 놓아주세요"}
            """;
    private static final String JEJU_BODY = """
            {"recipientName": "김철수", "recipientPhone": "02-1234-5678", "postalCode": "63100",
             "address": "제주특별자치도 제주시 첨단로 1", "addressDetail": "2층"}
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
        jdbcTemplate.update("DELETE FROM default_shipping_address");
        jdbcTemplate.update("DELETE FROM shipment_item");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    @Test
    void 기본_배송지가_없으면_204() throws Exception {
        // when & then
        getDefault(BUYER_ID)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void 처음_등록하면_200과_숫자만_남긴_연락처로_저장한_배송지를_돌려준다() throws Exception {
        // when
        ResultActions sut = putDefault(BUYER_ID, SEOUL_BODY);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientName", is("홍길동")))
                .andExpect(jsonPath("$.recipientPhone", is("01012345678")))
                .andExpect(jsonPath("$.postalCode", is("06236")))
                .andExpect(jsonPath("$.address", is("서울시 강남구 테헤란로 1")))
                .andExpect(jsonPath("$.addressDetail", is("101동 202호")))
                .andExpect(jsonPath("$.deliveryNote", is("문 앞에 놓아주세요")));
        assertThat(countRows(BUYER_ID)).isOne();
    }

    @Test
    void 등록한_기본_배송지를_조회한다() throws Exception {
        // given
        putDefault(BUYER_ID, SEOUL_BODY).andExpect(status().isOk());

        // when
        ResultActions sut = getDefault(BUYER_ID);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientName", is("홍길동")))
                .andExpect(jsonPath("$.recipientPhone", is("01012345678")))
                .andExpect(jsonPath("$.postalCode", is("06236")))
                .andExpect(jsonPath("$.address", is("서울시 강남구 테헤란로 1")))
                .andExpect(jsonPath("$.addressDetail", is("101동 202호")))
                .andExpect(jsonPath("$.deliveryNote", is("문 앞에 놓아주세요")));
    }

    @Test
    void 다시_등록하면_새_행을_만들지_않고_배송지를_바꾼다() throws Exception {
        // given
        putDefault(BUYER_ID, SEOUL_BODY).andExpect(status().isOk());

        // when
        putDefault(BUYER_ID, JEJU_BODY).andExpect(status().isOk());

        // then
        getDefault(BUYER_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientName", is("김철수")))
                .andExpect(jsonPath("$.recipientPhone", is("0212345678")))
                .andExpect(jsonPath("$.postalCode", is("63100")))
                .andExpect(jsonPath("$.deliveryNote", nullValue()));
        assertThat(countRows(BUYER_ID)).isOne();
    }

    @Test
    void 다른_회원의_기본_배송지는_보이지_않는다() throws Exception {
        // given
        putDefault(BUYER_ID, SEOUL_BODY).andExpect(status().isOk());

        // when & then
        getDefault(OTHER_MEMBER_ID).andExpect(status().isNoContent());
    }

    @Test
    void 기본_배송지를_바꿔도_기존_shipment의_배송지는_그대로다() throws Exception {
        // given
        Long shipmentId = shipmentRepository.save(shipment(1L, 10L, item(1L))).getId();

        // when
        putDefault(BUYER_ID, JEJU_BODY).andExpect(status().isOk());

        // then
        assertThat(jdbcTemplate.queryForObject("SELECT postal_code FROM shipment WHERE id = ?", String.class, shipmentId))
                .isEqualTo("06236");
    }

    @Test
    void 우편번호가_숫자_5자리가_아니면_422_VALIDATION_FAILED() throws Exception {
        // given
        String body = SEOUL_BODY.replace("06236", "1234");

        // when & then
        putDefault(BUYER_ID, body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
        assertThat(countRows(BUYER_ID)).isZero();
    }

    @Test
    void 일반_회원_권한이_없으면_조회는_403() throws Exception {
        // when & then
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 일반_회원_권한이_없으면_등록도_403이고_저장하지_않는다() throws Exception {
        // when & then
        mockMvc.perform(put(URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SEOUL_BODY))
                .andExpect(status().isForbidden());
        assertThat(countRows(BUYER_ID)).isZero();
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        // when & then
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    private ResultActions getDefault(Long memberId) throws Exception {
        return mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "BUYER")));
    }

    private ResultActions putDefault(Long memberId, String body) throws Exception {
        return mockMvc.perform(put(URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Integer countRows(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM default_shipping_address WHERE member_id = ?", Integer.class, memberId);
    }
}
