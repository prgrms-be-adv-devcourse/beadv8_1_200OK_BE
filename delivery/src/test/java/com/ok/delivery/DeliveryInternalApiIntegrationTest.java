package com.ok.delivery;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.ok.delivery.adapter.out.persistence.SellerShippingProfileJpaRepository;
import com.ok.delivery.domain.SellerShippingProfile;
import com.ok.delivery.domain.SellerShippingSetting;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 주문 모듈의 DeliveryApiClient가 읽는 응답 형식(sellers[sellerId, baseShippingFee, freeShippingThreshold, shippingFee, regionSurcharge])과
// 검증 실패 응답, 견적 API와 같은 금액을 확인한다. 계산 규칙 자체는 ShippingFeePolicyTest, 서비스 조합은 ShippingFeesServiceTest가 맡는다.
// 설정을 저장하지 않은 판매자는 기본값(3,000원, 5만원 이상 무료)으로 계산된다.
class DeliveryInternalApiIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/internal/v1/delivery/shipping-fees";
    private static final String QUOTE_URL = "/api/v1/shipments/fee-quotes";
    // 기본 설정과 다른 설정을 저장해 두는 판매자들
    private static final long NO_FREE_SHIPPING_SELLER_ID = 910_001L;

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    SellerShippingProfileJpaRepository sellerShippingProfileRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM seller_shipping_profile");
    }

    @Test
    void 판매자별_무료배송_기준을_적용하고_설정_값을_함께_돌려준다() throws Exception {
        // given
        String body = """
                {"postalCode": "06236", "sellers": [
                    {"sellerId": 10, "itemAmount": 49999},
                    {"sellerId": 20, "itemAmount": 50000}
                ]}
                """;

        // when
        ResultActions sut = requestShippingFees(body);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.sellers", hasSize(2)))
                .andExpect(jsonPath("$.sellers[0].sellerId", is(10)))
                .andExpect(jsonPath("$.sellers[0].baseShippingFee", is(3_000)))
                .andExpect(jsonPath("$.sellers[0].freeShippingThreshold", is(50_000)))
                .andExpect(jsonPath("$.sellers[0].shippingFee", is(3_000)))
                .andExpect(jsonPath("$.sellers[0].regionSurcharge", is(0)))
                .andExpect(jsonPath("$.sellers[1].sellerId", is(20)))
                .andExpect(jsonPath("$.sellers[1].baseShippingFee", is(3_000)))
                .andExpect(jsonPath("$.sellers[1].freeShippingThreshold", is(50_000)))
                .andExpect(jsonPath("$.sellers[1].shippingFee", is(0)))
                .andExpect(jsonPath("$.sellers[1].regionSurcharge", is(0)));
    }

    @Test
    void 도서산간은_무료배송이어도_판매자마다_추가_운임이_붙는다() throws Exception {
        // given
        String body = """
                {"postalCode": "63100", "sellers": [
                    {"sellerId": 10, "itemAmount": 10000},
                    {"sellerId": 20, "itemAmount": 50000}
                ]}
                """;

        // when
        ResultActions sut = requestShippingFees(body);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.sellers[0].shippingFee", is(3_000)))
                .andExpect(jsonPath("$.sellers[0].regionSurcharge", is(3_000)))
                .andExpect(jsonPath("$.sellers[1].shippingFee", is(0)))
                .andExpect(jsonPath("$.sellers[1].regionSurcharge", is(3_000)));
    }

    @Test
    void 무료배송_기준이_없는_판매자는_금액이_커도_기본_배송비를_받고_기준은_null이다() throws Exception {
        // given
        sellerShippingProfileRepository.save(SellerShippingProfile.create(
                NO_FREE_SHIPPING_SELLER_ID, new SellerShippingSetting(5_000, null, LocalTime.of(15, 0))));
        String body = """
                {"postalCode": "06236", "sellers": [{"sellerId": %d, "itemAmount": 10000000}]}
                """.formatted(NO_FREE_SHIPPING_SELLER_ID);

        // when
        ResultActions sut = requestShippingFees(body);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.sellers[0].baseShippingFee", is(5_000)))
                .andExpect(jsonPath("$.sellers[0].freeShippingThreshold", nullValue()))
                .andExpect(jsonPath("$.sellers[0].shippingFee", is(5_000)));
    }

    @Test
    void 응답은_요청한_모든_판매자를_요청_순서대로_담는다() throws Exception {
        // given
        String body = """
                {"postalCode": "06236", "sellers": [
                    {"sellerId": 30, "itemAmount": 1000},
                    {"sellerId": 10, "itemAmount": 1000},
                    {"sellerId": 20, "itemAmount": 1000}
                ]}
                """;

        // when
        ResultActions sut = requestShippingFees(body);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.sellers", hasSize(3)))
                .andExpect(jsonPath("$.sellers[0].sellerId", is(30)))
                .andExpect(jsonPath("$.sellers[1].sellerId", is(10)))
                .andExpect(jsonPath("$.sellers[2].sellerId", is(20)));
    }

    @ParameterizedTest
    @MethodSource("견적과_비교할_요청")
    void 같은_입력이면_견적_API와_같은_금액이다(String body) throws Exception {
        // given
        sellerShippingProfileRepository.save(SellerShippingProfile.create(
                NO_FREE_SHIPPING_SELLER_ID, new SellerShippingSetting(5_000, null, LocalTime.of(15, 0))));

        // when
        String fees = requestShippingFees(body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String quote = mockMvc.perform(post(QUOTE_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // then
        DocumentContext feesJson = JsonPath.parse(fees);
        DocumentContext quoteJson = JsonPath.parse(quote);
        int count = feesJson.read("$.sellers.length()", Integer.class);
        assertThat(count).isEqualTo(quoteJson.read("$.sellers.length()", Integer.class));
        for (int i = 0; i < count; i++) {
            String seller = "$.sellers[" + i + "]";
            assertSoftly(softly -> {
                softly.assertThat(feesJson.read(seller + ".shippingFee", Integer.class))
                        .isEqualTo(quoteJson.read(seller + ".baseShippingFee", Integer.class));
                softly.assertThat(feesJson.read(seller + ".regionSurcharge", Integer.class))
                        .isEqualTo(quoteJson.read(seller + ".regionSurcharge", Integer.class));
            });
        }
    }

    static Stream<String> 견적과_비교할_요청() {
        return Stream.of(
                """
                {"postalCode": "06236", "sellers": [
                    {"sellerId": 10, "itemAmount": 49999}, {"sellerId": 20, "itemAmount": 50000}, {"sellerId": 910001, "itemAmount": 999999}
                ]}
                """,
                """
                {"postalCode": "63100", "sellers": [
                    {"sellerId": 10, "itemAmount": 0}, {"sellerId": 20, "itemAmount": 50000}, {"sellerId": 910001, "itemAmount": 999999}
                ]}
                """);
    }

    @ParameterizedTest
    @MethodSource("형식에_맞지_않는_요청")
    void 요청_값이_형식에_맞지_않거나_판매자가_중복되면_검증_실패로_거절한다(String body) throws Exception {
        // when & then
        requestShippingFees(body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    static Stream<String> 형식에_맞지_않는_요청() {
        return Stream.of(
                // 우편번호 없음, 형식 오류
                """
                {"sellers": [{"sellerId": 10, "itemAmount": 1000}]}
                """,
                """
                {"postalCode": "", "sellers": [{"sellerId": 10, "itemAmount": 1000}]}
                """,
                """
                {"postalCode": "6310a", "sellers": [{"sellerId": 10, "itemAmount": 1000}]}
                """,
                """
                {"postalCode": "631000", "sellers": [{"sellerId": 10, "itemAmount": 1000}]}
                """,
                // 판매자 목록, 항목
                """
                {"postalCode": "63100", "sellers": []}
                """,
                """
                {"postalCode": "63100"}
                """,
                """
                {"postalCode": "63100", "sellers": [{"sellerId": 10}]}
                """,
                """
                {"postalCode": "63100", "sellers": [{"sellerId": 10, "itemAmount": -1}]}
                """,
                // 판매자 중복
                """
                {"postalCode": "63100", "sellers": [
                    {"sellerId": 10, "itemAmount": 1000},
                    {"sellerId": 10, "itemAmount": 2000}
                ]}
                """);
    }

    private ResultActions requestShippingFees(String body) throws Exception {
        return mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
