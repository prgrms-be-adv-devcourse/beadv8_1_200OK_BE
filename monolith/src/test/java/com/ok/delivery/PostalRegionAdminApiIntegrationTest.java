package com.ok.delivery;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 관리자 API를 호출해, 권한·요청 검증·토큰의 관리자 ID가 이력까지 이어지는지 확인한다.
// 등록·수정·삭제의 업무 규칙은 DeliveryRegionServiceAdminIntegrationTest가 맡는다.
class PostalRegionAdminApiIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/postal-regions";
    // 초기 데이터에 없는 번호라 테스트 행이 시드와 겹치지 않는다
    private static final String TEST_POSTAL_CODE = "99995";
    private static final Long ADMIN_ID = 9_001L;
    private static final String REGISTER_BODY = """
            {"postalCode": "99995", "regionGroup": "JEJU_ISLANDS", "extraDeliveryDays": 2}
            """;

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

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
        // 이력이 우편번호를 외래키로 참조하므로 이력부터 지운다
        jdbcTemplate.update("DELETE FROM remote_area_postal_code_history WHERE postal_code = ?", TEST_POSTAL_CODE);
        jdbcTemplate.update("DELETE FROM remote_area_postal_code WHERE postal_code = ?", TEST_POSTAL_CODE);
    }

    @Test
    void 관리자가_등록하면_201과_등록값을_돌려주고_이력에_토큰의_관리자_ID가_남는다() throws Exception {
        // when
        ResultActions sut = register(tokens.bearer(ADMIN_ID, "ADMIN"), REGISTER_BODY);

        // then
        sut.andExpect(status().isCreated())
                .andExpect(jsonPath("$.postalCode", is(TEST_POSTAL_CODE)))
                .andExpect(jsonPath("$.regionGroup", is("JEJU_ISLANDS")))
                .andExpect(jsonPath("$.extraDeliveryDays", is(2)));
        Long changedBy = jdbcTemplate.queryForObject(
                "SELECT changed_by FROM remote_area_postal_code_history WHERE postal_code = ?", Long.class, TEST_POSTAL_CODE);
        assertThat(changedBy).isEqualTo(ADMIN_ID);
    }

    @Test
    void 관리자가_아니면_403() throws Exception {
        // when & then
        register(tokens.bearer(ADMIN_ID, "BUYER", "SELLER"), REGISTER_BODY)
                .andExpect(status().isForbidden());
    }

    @Test
    void 요청_값이_형식에_맞지_않으면_422() throws Exception {
        // given
        String body = """
                {"postalCode": "6300a", "regionGroup": "JEJU_ISLANDS", "extraDeliveryDays": -1}
                """;

        // when & then
        register(tokens.bearer(ADMIN_ID, "ADMIN"), body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    @Test
    void 관리자가_삭제하면_204() throws Exception {
        // given
        register(tokens.bearer(ADMIN_ID, "ADMIN"), REGISTER_BODY).andExpect(status().isCreated());

        // when & then
        mockMvc.perform(delete(URL + "/" + TEST_POSTAL_CODE)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isNoContent());
    }

    @Test
    void 관리자가_수정하면_200과_수정값을_돌려준다() throws Exception {
        // given
        register(tokens.bearer(ADMIN_ID, "ADMIN"), REGISTER_BODY).andExpect(status().isCreated());
        String body = """
                {"regionGroup": "ULLEUNG", "extraDeliveryDays": 3}
                """;

        // when
        ResultActions sut = update(tokens.bearer(ADMIN_ID, "ADMIN"), TEST_POSTAL_CODE, body);

        // then
        sut.andExpect(status().isOk())
                .andExpect(jsonPath("$.postalCode", is(TEST_POSTAL_CODE)))
                .andExpect(jsonPath("$.regionGroup", is("ULLEUNG")))
                .andExpect(jsonPath("$.extraDeliveryDays", is(3)));
    }

    @Test
    void 수정_요청_값이_형식에_맞지_않으면_422() throws Exception {
        // given
        String body = """
                {"regionGroup": " ", "extraDeliveryDays": -1}
                """;

        // when & then
        update(tokens.bearer(ADMIN_ID, "ADMIN"), TEST_POSTAL_CODE, body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    @Test
    void 등록되지_않은_우편번호를_수정하면_404() throws Exception {
        // given
        String body = """
                {"regionGroup": "ULLEUNG", "extraDeliveryDays": 3}
                """;

        // when & then
        update(tokens.bearer(ADMIN_ID, "ADMIN"), TEST_POSTAL_CODE, body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/postal-region-not-found")));
    }

    @Test
    void 목록은_기본_25건씩_페이지_정보와_함께_돌려준다() throws Exception {
        // when & then
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(25)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(25)))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber());
    }

    @Test
    void 페이지_번호가_음수면_서버_오류가_아니라_422() throws Exception {
        // when & then
        mockMvc.perform(get(URL).param("page", "-1")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    private ResultActions register(String bearer, String body) throws Exception {
        return mockMvc.perform(post(URL)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions update(String bearer, String postalCode, String body) throws Exception {
        return mockMvc.perform(put(URL + "/" + postalCode)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
