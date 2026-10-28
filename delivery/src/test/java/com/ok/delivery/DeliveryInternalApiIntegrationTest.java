package com.ok.delivery;

import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 주문 모듈의 DeliveryApiClient가 읽는 응답 형식(zipCode, remoteArea, additionalFee)과 검증 실패 응답을 확인한다.
// 지역 판정 규칙은 DeliveryRegionServiceIntegrationTest가 맡는다.
class DeliveryInternalApiIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/internal/v1/delivery/remote-area-fee";
    // 초기 데이터에 없는 번호라 테스트 행이 시드와 겹치지 않는다
    private static final String TEST_POSTAL_CODE = "99994";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM remote_area_postal_code WHERE postal_code = ?", TEST_POSTAL_CODE);
    }

    @Test
    void 도서산간_우편번호는_도서산간_여부와_추가_운임을_돌려준다() throws Exception {
        // when & then
        mockMvc.perform(get(URL).param("zipCode", "63100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zipCode", is("63100")))
                .andExpect(jsonPath("$.remoteArea", is(true)))
                .andExpect(jsonPath("$.additionalFee", is(3_000)));
    }

    @Test
    void 등록되지_않은_우편번호는_일반_지역이고_추가_운임이_0원이다() throws Exception {
        // when & then
        mockMvc.perform(get(URL).param("zipCode", "06236"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zipCode", is("06236")))
                .andExpect(jsonPath("$.remoteArea", is(false)))
                .andExpect(jsonPath("$.additionalFee", is(0)));
    }

    @Test
    void 추가_운임이_없는_산간_지역도_도서산간으로_응답한다() throws Exception {
        // given
        jdbcTemplate.update(
                "INSERT INTO remote_area_postal_code (postal_code, region_group, extra_delivery_days, created_at, updated_at) "
                        + "VALUES (?, 'MOUNTAIN', 1, NOW(6), NOW(6))",
                TEST_POSTAL_CODE);

        // when & then
        mockMvc.perform(get(URL).param("zipCode", TEST_POSTAL_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remoteArea", is(true)))
                .andExpect(jsonPath("$.additionalFee", is(0)));
    }

    @ParameterizedTest
    @MethodSource("형식에_맞지_않는_우편번호")
    void 우편번호가_숫자_5자리가_아니면_422(String zipCode) throws Exception {
        // when & then
        mockMvc.perform(get(URL).param("zipCode", zipCode))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    static Stream<String> 형식에_맞지_않는_우편번호() {
        return Stream.of("6310", "631000", "6310a", "63-10", "", " ");
    }

    @Test
    void 우편번호가_없으면_422() throws Exception {
        // when & then
        mockMvc.perform(get(URL))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }
}
