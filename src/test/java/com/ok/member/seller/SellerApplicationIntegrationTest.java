package com.ok.member.seller;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토큰으로 판매자 신청·조회 API를 호출해, 경계 검증(토큰, 권한, 요청 본문)부터 DB 유니크 제약까지 실제와 같게 확인한다.
class SellerApplicationIntegrationTest extends AbstractIntegrationTest {

    private static final String APPLICATIONS_URL = "/api/v1/sellers/me/applications";
    private static final String BUSINESS_NUMBER = "512-81-04273";
    private static final LocalDateTime APPLIED_AT = LocalDateTime.of(2026, 10, 18, 14, 30);
    private static final LocalDateTime REVIEW_STARTED_AT = LocalDateTime.of(2026, 10, 19, 9, 0);
    private static final LocalDateTime DECIDED_AT = LocalDateTime.of(2026, 10, 20, 11, 0);
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

    private Long memberId;

    private Long reviewerId;

    @BeforeEach
    void setUp() {
        // 보안 필터를 적용해야 토큰 검증과 401·403 처리가 실제와 같게 동작한다
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        memberId = saveMember("seller01", "seller01@example.com");
        // reviewer_id는 member를 외래키로 참조한다. 관리자 회원 생성 메서드가 아직 없어, FK를 채울 회원을 따로 만든다
        reviewerId = saveMember("admin01", "admin01@example.com");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 신청에_성공하면_201과_신청_id를_반환하고_정규화한_값으로_심사_대기_신청을_저장한다() throws Exception {
        // when
        ResultActions result = performApply(memberId, applyBody("5128104273"));

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationId").isNumber());

        List<SellerApplication> applications = sellerApplicationRepository.findAllByMemberIdOrderByAppliedAtDescIdDesc(memberId);
        assertThat(applications).hasSize(1);
        SellerApplication sut = applications.getFirst();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(SellerApplicationStatus.PENDING);
            softly.assertThat(sut.getBusinessNumber()).isEqualTo(BUSINESS_NUMBER);
            softly.assertThat(sut.getStoreName()).isEqualTo("청송 햇살농원");
            softly.assertThat(sut.getAppliedAt()).isNotNull();
        });
    }

    @Test
    void 토큰_없이_신청하면_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(post(APPLICATIONS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyBody(BUSINESS_NUMBER)));

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_계정은_판매자_신청을_할_수_없다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(post(APPLICATIONS_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyBody(BUSINESS_NUMBER)));

        // then
        result.andExpect(status().isForbidden());
        assertThat(sellerApplicationRepository.count()).isZero();
    }

    @Test
    void 요청_값이_유효하지_않으면_422와_필드별_오류를_반환한다() throws Exception {
        // given
        String body = jsonMapper.writeValueAsString(Map.of(
                "storeName", " ",
                "representativeName", "김영수",
                "businessNumber", "512-81-0427",
                "mailOrderNumber", "제2026-경북청송-0031호"));

        // when
        ResultActions result = performApply(memberId, body);

        // then
        result.andExpect(status().is(422))
                .andExpect(jsonPath("$.errors[*].path", hasItem("storeName")))
                .andExpect(jsonPath("$.errors[*].path", hasItem("businessNumber")));
    }

    @Test
    void 심사_대기_중인_신청이_있으면_409와_진행_중_신청_type을_반환한다() throws Exception {
        // given
        performApply(memberId, applyBody(BUSINESS_NUMBER)).andExpect(status().isCreated());

        // when
        ResultActions result = performApply(memberId, applyBody("123-45-67890"));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/member/seller-application-in-progress")));
    }

    @Test
    void 승인된_회원이_다시_신청하면_409와_이미_판매자_type을_반환한다() throws Exception {
        // given
        saveApproved(memberId, BUSINESS_NUMBER);

        // when
        ResultActions result = performApply(memberId, applyBody("123-45-67890"));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/member/already-seller")));
    }

    @Test
    void 거절된_뒤에는_같은_사업자등록번호로_바로_다시_신청할_수_있다() throws Exception {
        // given
        saveRejected(memberId, BUSINESS_NUMBER);

        // when
        ResultActions result = performApply(memberId, applyBody(BUSINESS_NUMBER));

        // then
        result.andExpect(status().isCreated());
        assertThat(sellerApplicationRepository.findAllByMemberIdOrderByAppliedAtDescIdDesc(memberId)).hasSize(2);
    }

    @Test
    void 다른_회원이_신청한_사업자등록번호면_409와_사업자등록번호_중복_type을_반환한다() throws Exception {
        // given
        Long otherMemberId = saveMember("seller02", "seller02@example.com");
        performApply(otherMemberId, applyBody(BUSINESS_NUMBER)).andExpect(status().isCreated());

        // when
        ResultActions result = performApply(memberId, applyBody("5128104273"));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/member/duplicate-business-number")));
    }

    @Test
    void 신청_내역은_최근_신청부터_심사_시작일과_승인일_거절일과_거절_사유를_보여_준다() throws Exception {
        // given
        Long rejectedId = saveRejected(memberId, BUSINESS_NUMBER);
        Long approvedId = saveApproved(memberId, BUSINESS_NUMBER);

        // when
        ResultActions result = mockMvc.perform(get(APPLICATIONS_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "BUYER")));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.applications", hasSize(2)))
                .andExpect(jsonPath("$.applications[0].applicationId").value(approvedId.intValue()))
                .andExpect(jsonPath("$.applications[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.applications[0].appliedAt", startsWith("2026-10-18T14:30")))
                .andExpect(jsonPath("$.applications[0].reviewStartedAt", startsWith("2026-10-19T09:00")))
                .andExpect(jsonPath("$.applications[0].approvedAt", startsWith("2026-10-20T11:00")))
                .andExpect(jsonPath("$.applications[0].rejectedAt").value(nullValue()))
                .andExpect(jsonPath("$.applications[0].rejectReason").value(nullValue()))
                .andExpect(jsonPath("$.applications[1].applicationId").value(rejectedId.intValue()))
                .andExpect(jsonPath("$.applications[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.applications[1].rejectedAt", startsWith("2026-10-20T11:00")))
                .andExpect(jsonPath("$.applications[1].rejectReason").value(REJECT_REASON))
                .andExpect(jsonPath("$.applications[1].approvedAt").value(nullValue()));
    }

    @Test
    void 신청한_적이_없으면_빈_신청_내역을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get(APPLICATIONS_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "BUYER")));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.applications", hasSize(0)));
    }

    @Test
    void 신청_정보는_상호명_대표자명_사업자등록번호_신청일을_보여_준다() throws Exception {
        // given
        Long applicationId = sellerApplicationRepository.saveAndFlush(pending(memberId, BUSINESS_NUMBER)).getId();

        // when
        ResultActions result = mockMvc.perform(get(APPLICATIONS_URL + "/{applicationId}", applicationId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "BUYER")));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(applicationId.intValue()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.storeName").value("청송 햇살농원"))
                .andExpect(jsonPath("$.representativeName").value("김영수"))
                .andExpect(jsonPath("$.businessNumber").value(BUSINESS_NUMBER))
                .andExpect(jsonPath("$.appliedAt", startsWith("2026-10-18T14:30")));
    }

    @Test
    void 다른_회원의_신청_정보는_404를_반환한다() throws Exception {
        // given
        Long otherMemberId = saveMember("seller02", "seller02@example.com");
        Long applicationId = sellerApplicationRepository.saveAndFlush(pending(otherMemberId, BUSINESS_NUMBER)).getId();

        // when
        ResultActions result = mockMvc.perform(get(APPLICATIONS_URL + "/{applicationId}", applicationId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(memberId, "BUYER")));

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type", endsWith("/member/seller-application-not-found")));
    }

    @Test
    void DB_unique_제약이_한_회원의_진행_중_신청을_하나로_막는다() {
        // given
        sellerApplicationRepository.saveAndFlush(pending(memberId, BUSINESS_NUMBER));
        SellerApplication duplicated = pending(memberId, "123-45-67890");

        // when & then
        assertThatThrownBy(() -> sellerApplicationRepository.saveAndFlush(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_seller_application_active_member_id");
    }

    @Test
    void 같은_신청을_동시에_처리하면_나중에_저장하는_쪽이_낙관적_락으로_실패한다() {
        // given: 관리자 두 명이 같은 버전의 신청을 각각 읽었다
        Long applicationId = sellerApplicationRepository.saveAndFlush(pending(memberId, BUSINESS_NUMBER)).getId();
        SellerApplication first = sellerApplicationRepository.findById(applicationId).orElseThrow();
        SellerApplication second = sellerApplicationRepository.findById(applicationId).orElseThrow();

        first.reject(reviewerId, REJECT_REASON, DECIDED_AT);
        sellerApplicationRepository.saveAndFlush(first);

        // when & then: 이미 버전이 올라가 나중 쪽은 덮어쓰지 못한다
        second.startReview(REVIEW_STARTED_AT);
        assertThatThrownBy(() -> sellerApplicationRepository.saveAndFlush(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(sellerApplicationRepository.findById(applicationId).orElseThrow().getStatus())
                .isEqualTo(SellerApplicationStatus.REJECTED);
    }

    @Test
    void DB_unique_제약은_거절된_신청을_여러_건_허용한다() {
        // given
        saveRejected(memberId, BUSINESS_NUMBER);

        // when
        saveRejected(memberId, BUSINESS_NUMBER);

        // then
        assertThat(sellerApplicationRepository.findAllByMemberIdOrderByAppliedAtDescIdDesc(memberId)).hasSize(2);
    }

    private ResultActions performApply(Long applicantId, String body) throws Exception {
        return mockMvc.perform(post(APPLICATIONS_URL)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(applicantId, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String applyBody(String businessNumber) {
        return jsonMapper.writeValueAsString(Map.of(
                "storeName", "청송 햇살농원",
                "representativeName", "김영수",
                "businessNumber", businessNumber,
                "mailOrderNumber", "제2026-경북청송-0031호"));
    }

    private Long saveMember(String loginId, String email) {
        return memberRepository.saveAndFlush(Member.createUser(loginId, "{bcrypt}encoded", email,
                "김영수", loginId, LocalDateTime.of(2026, 10, 1, 0, 0))).getId();
    }

    private SellerApplication pending(Long applicantId, String businessNumber) {
        return SellerApplication.apply(applicantId, "청송 햇살농원", "김영수", businessNumber,
                "제2026-경북청송-0031호", APPLIED_AT);
    }

    private Long saveApproved(Long applicantId, String businessNumber) {
        SellerApplication application = pending(applicantId, businessNumber);
        application.startReview(REVIEW_STARTED_AT);
        application.approve(reviewerId, DECIDED_AT);
        return sellerApplicationRepository.saveAndFlush(application).getId();
    }

    private Long saveRejected(Long applicantId, String businessNumber) {
        SellerApplication application = pending(applicantId, businessNumber);
        application.reject(reviewerId, REJECT_REASON, DECIDED_AT);
        return sellerApplicationRepository.saveAndFlush(application).getId();
    }

    // 판매자 신청 테이블이 member를 외래키로 참조하므로 신청을 먼저 지운다
    private void cleanUp() {
        sellerApplicationRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

}
