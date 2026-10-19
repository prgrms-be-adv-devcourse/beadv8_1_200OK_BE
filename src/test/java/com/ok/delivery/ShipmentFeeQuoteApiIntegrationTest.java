package com.ok.delivery;

import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청 검증, 지역 판정·판매자 기본 설정·배송비 계산이 이어진 응답 형식을 확인한다.
// 예정일은 실제 시각 기준이라 값이 아닌 존재만 보고, 날짜 계산은 ShippingFeeQuoteServiceTest가 맡는다.
// 판매자 배송 설정 저장소가 아직 없어 모든 판매자가 기본값(3,000원, 5만원 이상 무료)으로 계산된다.
class ShipmentFeeQuoteApiIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/shipments/fee-quotes";

    @Autowired
    WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 일반_지역은_판매자별로_무료배송_기준을_적용한다() throws Exception {
        // given
        String body = """
                {"postalCode": "06236", "sellers": [
                    {"sellerId": 10, "itemAmount": 49999},
                    {"sellerId": 20, "itemAmount": 50000}
                ]}
                """;

        // when
        ResultActions sut = quote(body);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.regionGroup", is("GENERAL")))
                .andExpect(jsonPath("$.sellers", hasSize(2)))
                .andExpect(jsonPath("$.sellers[0].sellerId", is(10)))
                .andExpect(jsonPath("$.sellers[0].baseShippingFee", is(3_000)))
                .andExpect(jsonPath("$.sellers[0].regionSurcharge", is(0)))
                .andExpect(jsonPath("$.sellers[0].shippingFee", is(3_000)))
                .andExpect(jsonPath("$.sellers[0].expectedShipDate", notNullValue()))
                .andExpect(jsonPath("$.sellers[0].arrivalStartDate", notNullValue()))
                .andExpect(jsonPath("$.sellers[0].arrivalEndDate", notNullValue()))
                .andExpect(jsonPath("$.sellers[1].sellerId", is(20)))
                .andExpect(jsonPath("$.sellers[1].baseShippingFee", is(0)))
                .andExpect(jsonPath("$.sellers[1].shippingFee", is(0)))
                .andExpect(jsonPath("$.totalShippingFee", is(3_000)));
    }

    @Test
    void 도서산간은_판매자마다_추가_운임이_붙는다() throws Exception {
        // given
        String body = """
                {"postalCode": "63100", "sellers": [
                    {"sellerId": 10, "itemAmount": 10000},
                    {"sellerId": 20, "itemAmount": 50000}
                ]}
                """;

        // when
        ResultActions sut = quote(body);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.regionGroup", is("JEJU_MAIN")))
                .andExpect(jsonPath("$.sellers[0].baseShippingFee", is(3_000)))
                .andExpect(jsonPath("$.sellers[0].regionSurcharge", is(3_000)))
                .andExpect(jsonPath("$.sellers[0].shippingFee", is(6_000)))
                .andExpect(jsonPath("$.sellers[1].baseShippingFee", is(0)))
                .andExpect(jsonPath("$.sellers[1].regionSurcharge", is(3_000)))
                .andExpect(jsonPath("$.sellers[1].shippingFee", is(3_000)))
                .andExpect(jsonPath("$.totalShippingFee", is(9_000)));
    }

    @ParameterizedTest
    @MethodSource("형식에_맞지_않는_요청")
    void 요청_값이_형식에_맞지_않으면_422(String body) throws Exception {
        // when & then
        quote(body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    static Stream<String> 형식에_맞지_않는_요청() {
        return Stream.of(
                // 우편번호 형식
                """
                {"postalCode": "6310a", "sellers": [{"sellerId": 10, "itemAmount": 1000}]}
                """,
                """
                {"sellers": [{"sellerId": 10, "itemAmount": 1000}]}
                """,
                // 판매자 목록
                """
                {"postalCode": "63100", "sellers": []}
                """,
                """
                {"postalCode": "63100"}
                """,
                """
                {"postalCode": "63100", "sellers": [null]}
                """,
                // 판매자 항목
                """
                {"postalCode": "63100", "sellers": [{"itemAmount": 1000}]}
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

    private ResultActions quote(String body) throws Exception {
        return mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
