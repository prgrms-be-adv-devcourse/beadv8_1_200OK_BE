package com.ok.member.seller.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.domain.SellerApplication;
import com.ok.member.seller.domain.SellerApplicationStatus;
import com.ok.member.seller.exception.SellerErrorCode;
import com.ok.member.seller.repository.SellerApplicationRepository;
import com.ok.member.seller.service.dto.SellerApplicationAdminResult;
import com.ok.member.seller.service.dto.SellerApplicationRejectCommand;
import com.ok.member.seller.service.dto.SellerApplicationSearchQuery;
import com.ok.member.seller.service.dto.SellerApplyCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SellerApplicationServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final String BUSINESS_NUMBER = "512-81-04273";
    private static final Long APPLICATION_ID = 10L;
    private static final Long ADMIN_ID = 100L;
    // 2026-10-18 14:30 (Asia/Seoul)
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T05:30:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 14, 30);

    @Mock
    SellerApplicationRepository sellerApplicationRepository;

    @Mock
    SellerApplicationDuplicateValidator duplicateValidator;

    @Mock
    SellerEventPublisher sellerEventPublisher;

    SellerApplicationService sellerApplicationService;

    private final SellerApplyCommand command =
            new SellerApplyCommand("청송 햇살농원", "김영수", BUSINESS_NUMBER, "제2026-경북청송-0031호");

    // @InjectMocks는 @Mock 필드만 생성자에 넣어 주므로 Clock을 넣어 직접 만든다
    @BeforeEach
    void setUp() {
        sellerApplicationService = new SellerApplicationService(
                sellerApplicationRepository, duplicateValidator, sellerEventPublisher, CLOCK);
    }

    @Test
    void 신청하면_현재_시각을_신청_일시로_심사_대기_신청을_저장한다() {
        // given
        given(sellerApplicationRepository.saveAndFlush(any(SellerApplication.class))).willAnswer(invocation -> {
            SellerApplication application = invocation.getArgument(0);
            ReflectionTestUtils.setField(application, "id", 10L);
            return application;
        });

        // when
        Long applicationId = sellerApplicationService.apply(MEMBER_ID, command);

        // then
        ArgumentCaptor<SellerApplication> captor = ArgumentCaptor.forClass(SellerApplication.class);
        then(sellerApplicationRepository).should().saveAndFlush(captor.capture());
        SellerApplication sut = captor.getValue();

        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(applicationId).isEqualTo(10L);
            softly.assertThat(sut.getMemberId()).isEqualTo(MEMBER_ID);
            softly.assertThat(sut.getStatus()).isEqualTo(SellerApplicationStatus.PENDING);
            softly.assertThat(sut.getAppliedAt()).isEqualTo(LocalDateTime.of(2026, 10, 18, 14, 30));
        });
    }

    @Test
    void 중복_검증에_실패하면_저장하지_않는다() {
        // given
        willThrow(new RestApiException(SellerErrorCode.SELLER_APPLICATION_IN_PROGRESS))
                .given(duplicateValidator).validate(MEMBER_ID, BUSINESS_NUMBER);

        // when & then
        assertThatThrownBy(() -> sellerApplicationService.apply(MEMBER_ID, command))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.SELLER_APPLICATION_IN_PROGRESS));
        then(sellerApplicationRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 저장_중_무결성_위반이_나면_검증기가_바꾼_예외를_던진다() {
        // given
        DataIntegrityViolationException violation = new DataIntegrityViolationException("duplicate");
        RestApiException converted = new RestApiException(SellerErrorCode.DUPLICATE_BUSINESS_NUMBER);
        given(sellerApplicationRepository.saveAndFlush(any(SellerApplication.class))).willThrow(violation);
        given(duplicateValidator.toDuplicateException(violation)).willReturn(converted);

        // when & then
        assertThatThrownBy(() -> sellerApplicationService.apply(MEMBER_ID, command))
                .isSameAs(converted);
    }

    @Test
    void 본인의_신청이_아니면_신청_정보를_찾을_수_없다() {
        // given
        given(sellerApplicationRepository.findByIdAndMemberId(99L, MEMBER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sellerApplicationService.getMyApplication(MEMBER_ID, 99L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND));
    }

    @Test
    void 관리자_목록은_상태를_생략하면_모든_신청을_오래된_신청부터_조회한다() {
        // given
        given(sellerApplicationRepository.findAll(any(Pageable.class))).willReturn(Page.empty());

        // when
        sellerApplicationService.getApplications(new SellerApplicationSearchQuery(null, 1, 10));

        // then: 정렬이 바뀌면 페이지 사이에서 신청이 겹치거나 빠진다
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        then(sellerApplicationRepository).should().findAll(captor.capture());
        Pageable sut = captor.getValue();

        assertSoftly(softly -> {
            softly.assertThat(sut.getPageNumber()).isEqualTo(1);
            softly.assertThat(sut.getPageSize()).isEqualTo(10);
            softly.assertThat(sut.getSort()).isEqualTo(Sort.by(Sort.Order.asc("appliedAt"), Sort.Order.asc("id")));
        });
    }

    @Test
    void 관리자_목록은_상태를_주면_그_상태의_신청만_조회한다() {
        // given
        given(sellerApplicationRepository.findAllByStatus(eq(SellerApplicationStatus.PENDING), any(Pageable.class)))
                .willReturn(Page.empty());

        // when
        sellerApplicationService.getApplications(new SellerApplicationSearchQuery("PENDING", 0, 20));

        // then
        then(sellerApplicationRepository).should()
                .findAllByStatus(eq(SellerApplicationStatus.PENDING), any(Pageable.class));
        then(sellerApplicationRepository).should(never()).findAll(any(Pageable.class));
    }

    @Test
    void 없는_신청은_관리자도_조회할_수_없다() {
        // given
        given(sellerApplicationRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sellerApplicationService.getApplication(99L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND));
    }

    @Test
    void 심사를_시작하면_현재_시각을_심사_시작_일시로_기록하고_이벤트는_발행하지_않는다() {
        // given
        SellerApplication application = savedApplication();
        given(sellerApplicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(application));

        // when
        SellerApplicationAdminResult sut = sellerApplicationService.startReview(APPLICATION_ID);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo("IN_REVIEW");
            softly.assertThat(sut.reviewStartedAt()).isEqualTo(NOW);
        });
        then(sellerApplicationRepository).should().flush();
        then(sellerEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void 승인하면_심사자와_승인_일시를_기록하고_같은_일시로_승인_이벤트를_발행한다() {
        // given
        SellerApplication application = savedApplication();
        application.startReview(NOW);
        given(sellerApplicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(application));

        // when
        SellerApplicationAdminResult sut = sellerApplicationService.approve(ADMIN_ID, APPLICATION_ID);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo("APPROVED");
            softly.assertThat(sut.approvedAt()).isEqualTo(NOW);
            softly.assertThat(sut.reviewerId()).isEqualTo(ADMIN_ID);
        });
        then(sellerEventPublisher).should().publishSellerApproved(MEMBER_ID, NOW);
    }

    @Test
    void 심사를_시작하지_않은_신청을_승인하면_이벤트를_발행하지_않는다() {
        // given
        given(sellerApplicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(savedApplication()));

        // when & then
        assertThatThrownBy(() -> sellerApplicationService.approve(ADMIN_ID, APPLICATION_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_SELLER_APPLICATION_STATUS));
        then(sellerEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void 다른_관리자가_먼저_처리해_버전이_어긋나면_409로_바꾸고_승인_이벤트를_발행하지_않는다() {
        // given
        SellerApplication application = savedApplication();
        application.startReview(NOW);
        given(sellerApplicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(application));
        willThrow(new ObjectOptimisticLockingFailureException(SellerApplication.class, APPLICATION_ID))
                .given(sellerApplicationRepository).flush();

        // when & then
        assertThatThrownBy(() -> sellerApplicationService.approve(ADMIN_ID, APPLICATION_ID))
                .isInstanceOfSatisfying(RestApiException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SellerErrorCode.SELLER_APPLICATION_CONCURRENT_MODIFICATION));
        then(sellerEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void 거절하면_심사자와_거절_일시와_사유를_기록하고_이벤트는_발행하지_않는다() {
        // given
        given(sellerApplicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(savedApplication()));

        // when
        SellerApplicationAdminResult sut = sellerApplicationService.reject(
                ADMIN_ID, APPLICATION_ID, new SellerApplicationRejectCommand("통신판매업 신고번호를 확인할 수 없습니다."));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo("REJECTED");
            softly.assertThat(sut.rejectedAt()).isEqualTo(NOW);
            softly.assertThat(sut.rejectReason()).isEqualTo("통신판매업 신고번호를 확인할 수 없습니다.");
            softly.assertThat(sut.reviewerId()).isEqualTo(ADMIN_ID);
        });
        then(sellerApplicationRepository).should().flush();
        then(sellerEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void 처리_일시는_DB_정밀도인_마이크로초까지만_기록한다() {
        // given: 나노초까지 주는 시계
        Clock nanoClock = Clock.fixed(Instant.parse("2026-10-18T05:30:00.123456789Z"), ZoneId.of("Asia/Seoul"));
        SellerApplicationService service = new SellerApplicationService(
                sellerApplicationRepository, duplicateValidator, sellerEventPublisher, nanoClock);
        given(sellerApplicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(savedApplication()));

        // when
        SellerApplicationAdminResult sut = service.startReview(APPLICATION_ID);

        // then: 응답에 담는 값이 나중에 DB에서 읽을 값과 같아야 한다
        assertThat(sut.reviewStartedAt()).isEqualTo(LocalDateTime.of(2026, 10, 18, 14, 30, 0, 123_456_000));
    }

    // 저장된 신청처럼 id가 있는 심사 대기 신청
    private static SellerApplication savedApplication() {
        SellerApplication application = SellerApplication.apply(
                MEMBER_ID, "청송 햇살농원", "김영수", BUSINESS_NUMBER, "제2026-경북청송-0031호", NOW);
        ReflectionTestUtils.setField(application, "id", APPLICATION_ID);
        return application;
    }

}
