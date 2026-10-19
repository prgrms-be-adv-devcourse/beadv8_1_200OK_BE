package com.ok.member.seller.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.domain.SellerApplication;
import com.ok.member.seller.domain.SellerApplicationStatus;
import com.ok.member.seller.exception.SellerErrorCode;
import com.ok.member.seller.repository.SellerApplicationRepository;
import com.ok.member.seller.service.dto.SellerApplyCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SellerApplicationServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final String BUSINESS_NUMBER = "512-81-04273";
    // 2026-10-18 14:30 (Asia/Seoul)
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T05:30:00Z"), ZoneId.of("Asia/Seoul"));

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

}
