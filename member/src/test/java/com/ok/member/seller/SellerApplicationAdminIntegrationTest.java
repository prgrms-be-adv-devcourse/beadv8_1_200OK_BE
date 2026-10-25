package com.ok.member.seller;

import com.jayway.jsonpath.JsonPath;
import com.ok.member.member.domain.Member;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.seller.domain.SellerApplication;
import com.ok.member.seller.domain.SellerApplicationStatus;
import com.ok.member.seller.repository.SellerApplicationRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 관리자 토큰으로 판매자 신청 심사 API를 호출해, 권한·요청 검증부터 상태 전이, 신청자 쪽에 보이는 결과까지 실제와 같게 확인한다.
class SellerApplicationAdminIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_URL = "/api/v1/admin/seller-applications";
    private static final String MY_APPLICATIONS_URL = "/api/v1/sellers/me/applications";
    private static final String BUSINESS_NUMBER = "512-81-04273";
    private static final String REJECT_REASON = "통신판매업 신고번호를 확인할 수 없습니다.";

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    SellerApplicationRepository sellerApplicationRepository;

    private MockMvc mockMvc;

    private Long applicantId;

    private Long adminId;

    @BeforeEach
    void setUp() {
        // 보안 필터를 적용해야 토큰 검증과 401·403 처리가 실제와 같게 동작한다
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        applicantId = saveMember("seller01", "seller01@example.com");
        // reviewer_id는 member를 외래키로 참조한다. 관리자 회원 생성 메서드가 아직 없어, 관리자 토큰의 회원을 일반 회원으로 만든다
        adminId = saveMember("admin01", "admin01@example.com");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 목록은_오래된_신청부터_페이지_정보와_함께_돌려준다() throws Exception {
        // given
        Long newerId = saveApplication(saveMember("seller02", "seller02@example.com"), "123-45-67890",
                LocalDateTime.of(2026, 10, 18, 15, 0));
        Long olderId = saveApplication(applicantId, BUSINESS_NUMBER, LocalDateTime.of(2026, 10, 18, 14, 0));

        // when
        ResultActions result = mockMvc.perform(asAdmin(get(ADMIN_URL)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].applicationId").value(olderId.intValue()))
                .andExpect(jsonPath("$.content[0].memberId").value(applicantId.intValue()))
                .andExpect(jsonPath("$.content[0].businessNumber").value(BUSINESS_NUMBER))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[1].applicationId").value(newerId.intValue()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void 목록은_상태로_거를_수_있다() throws Exception {
        // given
        Long pendingId = saveApplication(applicantId, BUSINESS_NUMBER, LocalDateTime.of(2026, 10, 18, 14, 0));
        SellerApplication rejected = pending(saveMember("seller02", "seller02@example.com"), "123-45-67890",
                LocalDateTime.of(2026, 10, 18, 13, 0));
        rejected.reject(adminId, REJECT_REASON, LocalDateTime.of(2026, 10, 18, 13, 30));
        sellerApplicationRepository.saveAndFlush(rejected);

        // when
        ResultActions result = mockMvc.perform(asAdmin(get(ADMIN_URL).param("status", "PENDING")));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].applicationId").value(pendingId.intValue()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @CsvSource({"status, WAITING", "page, -1", "size, 101"})
    void 목록_조회_조건이_올바르지_않으면_422를_반환한다(String name, String value) throws Exception {
        // when
        ResultActions result = mockMvc.perform(asAdmin(get(ADMIN_URL).param(name, value)));

        // then
        result.andExpect(status().is(422))
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    @Test
    void 상세는_통신판매업_신고번호까지_신청_정보_전체를_돌려준다() throws Exception {
        // given
        Long applicationId = saveApplication(applicantId, BUSINESS_NUMBER, LocalDateTime.of(2026, 10, 18, 14, 0));

        // when
        ResultActions result = mockMvc.perform(asAdmin(get(ADMIN_URL + "/{id}", applicationId)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(applicationId.intValue()))
                .andExpect(jsonPath("$.memberId").value(applicantId.intValue()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.storeName").value("청송 햇살농원"))
                .andExpect(jsonPath("$.representativeName").value("김영수"))
                .andExpect(jsonPath("$.businessNumber").value(BUSINESS_NUMBER))
                .andExpect(jsonPath("$.mailOrderNumber").value("제2026-경북청송-0031호"))
                .andExpect(jsonPath("$.reviewStartedAt").value(nullValue()))
                .andExpect(jsonPath("$.reviewerId").value(nullValue()));
    }

    @Test
    void 없는_신청은_404를_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(asAdmin(get(ADMIN_URL + "/{id}", 999_999L)));

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/member/seller-application-not-found")));
    }

    @Test
    void 심사_시작_후_승인하면_심사자가_기록되고_신청자의_내역에_같은_일시가_보인다() throws Exception {
        // given
        Long applicationId = saveApplication(applicantId, BUSINESS_NUMBER, LocalDateTime.of(2026, 10, 18, 14, 0));

        // when
        String reviewed = mockMvc.perform(asAdmin(post(ADMIN_URL + "/{id}/review", applicationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.reviewStartedAt", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String approved = mockMvc.perform(asAdmin(post(ADMIN_URL + "/{id}/approve", applicationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt", notNullValue()))
                .andExpect(jsonPath("$.reviewerId").value(adminId.intValue()))
                .andReturn().getResponse().getContentAsString();

        // then: 관리자가 처리한 일시가 신청자의 신청 내역에 그대로 보인다.
        // 응답은 저장 전 값이고 내역은 DB에서 읽은 값이라, 서비스가 DB 정밀도로 맞춰 두지 않으면 끝자리가 달라진다
        mockMvc.perform(get(MY_APPLICATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(applicantId, "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applications[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.applications[0].reviewStartedAt")
                        .value((String) JsonPath.read(reviewed, "$.reviewStartedAt")))
                .andExpect(jsonPath("$.applications[0].approvedAt")
                        .value((String) JsonPath.read(approved, "$.approvedAt")));
    }

    @Test
    void 심사를_시작하지_않은_신청을_승인하면_409를_반환한다() throws Exception {
        // given
        Long applicationId = saveApplication(applicantId, BUSINESS_NUMBER, LocalDateTime.of(2026, 10, 18, 14, 0));

        // when
        ResultActions result = mockMvc.perform(asAdmin(post(ADMIN_URL + "/{id}/approve", applicationId)));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/member/invalid-seller-application-status")));
        assertThat(sellerApplicationRepository.findById(applicationId).orElseThrow().getStatus())
                .isEqualTo(SellerApplicationStatus.PENDING);
    }

    @Test
    void 거절하면_사유와_심사자가_기록되고_신청자는_사유를_보고_바로_다시_신청할_수_있다() throws Exception {
        // given
        Long applicationId = saveApplication(applicantId, BUSINESS_NUMBER, LocalDateTime.of(2026, 10, 18, 14, 0));

        // when
        ResultActions result = mockMvc.perform(asAdmin(post(ADMIN_URL + "/{id}/reject", applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rejectBody("  " + REJECT_REASON + " ")));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value(REJECT_REASON))
                .andExpect(jsonPath("$.reviewerId").value(adminId.intValue()));

        mockMvc.perform(get(MY_APPLICATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(applicantId, "BUYER")))
                .andExpect(jsonPath("$.applications[0].rejectReason").value(REJECT_REASON));

        mockMvc.perform(post(MY_APPLICATIONS_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(applicantId, "BUYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody()))
                .andExpect(status().isCreated());
    }

    @Test
    void 거절_사유_없이_거절하면_422를_반환하고_상태를_바꾸지_않는다() throws Exception {
        // given
        Long applicationId = saveApplication(applicantId, BUSINESS_NUMBER, LocalDateTime.of(2026, 10, 18, 14, 0));

        // when
        ResultActions result = mockMvc.perform(asAdmin(post(ADMIN_URL + "/{id}/reject", applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rejectBody(" ")));

        // then
        result.andExpect(status().is(422))
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
        assertThat(sellerApplicationRepository.findById(applicationId).orElseThrow().getStatus())
                .isEqualTo(SellerApplicationStatus.PENDING);
    }

    @Test
    void 승인된_신청을_거절하면_409를_반환한다() throws Exception {
        // given
        SellerApplication application = pending(applicantId, BUSINESS_NUMBER, LocalDateTime.of(2026, 10, 18, 14, 0));
        application.startReview(LocalDateTime.of(2026, 10, 19, 9, 0));
        application.approve(adminId, LocalDateTime.of(2026, 10, 20, 11, 0));
        Long applicationId = sellerApplicationRepository.saveAndFlush(application).getId();

        // when
        ResultActions result = mockMvc.perform(asAdmin(post(ADMIN_URL + "/{id}/reject", applicationId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rejectBody(REJECT_REASON)));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/member/invalid-seller-application-status")));
        SellerApplication sut = sellerApplicationRepository.findById(applicationId).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(SellerApplicationStatus.APPROVED);
            softly.assertThat(sut.getRejectReason()).isNull();
        });
    }

    @Test
    void 없는_신청을_처리하면_404를_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(asAdmin(post(ADMIN_URL + "/{id}/review", 999_999L)));

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/member/seller-application-not-found")));
    }

    @Test
    void 관리자가_아니면_403을_반환하고_신청을_바꾸지_않는다() throws Exception {
        // given
        Long applicationId = saveApplication(applicantId, BUSINESS_NUMBER, LocalDateTime.of(2026, 10, 18, 14, 0));

        // when
        ResultActions list = mockMvc.perform(get(ADMIN_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(applicantId, "BUYER")));
        ResultActions review = mockMvc.perform(post(ADMIN_URL + "/{id}/review", applicationId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(applicantId, "BUYER", "SELLER")));

        // then
        list.andExpect(status().isForbidden());
        review.andExpect(status().isForbidden());
        assertThat(sellerApplicationRepository.findById(applicationId).orElseThrow().getStatus())
                .isEqualTo(SellerApplicationStatus.PENDING);
    }

    @Test
    void 토큰_없이_호출하면_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get(ADMIN_URL));

        // then
        result.andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(adminId, "ADMIN"));
    }

    private String rejectBody(String reason) {
        return jsonMapper.writeValueAsString(Map.of("reason", reason));
    }

    private String applyBody() {
        return jsonMapper.writeValueAsString(Map.of(
                "storeName", "청송 햇살농원",
                "representativeName", "김영수",
                "businessNumber", BUSINESS_NUMBER,
                "mailOrderNumber", "제2026-경북청송-0031호"));
    }

    private Long saveMember(String loginId, String email) {
        return memberRepository.saveAndFlush(Member.createUser(loginId, "{bcrypt}encoded", email,
                "김영수", loginId, LocalDateTime.of(2026, 10, 1, 0, 0))).getId();
    }

    private SellerApplication pending(Long memberId, String businessNumber, LocalDateTime appliedAt) {
        return SellerApplication.apply(memberId, "청송 햇살농원", "김영수", businessNumber,
                "제2026-경북청송-0031호", appliedAt);
    }

    private Long saveApplication(Long memberId, String businessNumber, LocalDateTime appliedAt) {
        return sellerApplicationRepository.saveAndFlush(pending(memberId, businessNumber, appliedAt)).getId();
    }

    // 판매자 신청이 member를 외래키로 참조하므로 신청을 먼저 지운다
    private void cleanUp() {
        sellerApplicationRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

}
