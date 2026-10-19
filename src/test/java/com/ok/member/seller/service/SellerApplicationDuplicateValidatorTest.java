package com.ok.member.seller.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.domain.SellerApplication;
import com.ok.member.seller.exception.SellerErrorCode;
import com.ok.member.seller.repository.SellerApplicationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SellerApplicationDuplicateValidatorTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long REVIEWER_ID = 100L;
    private static final String BUSINESS_NUMBER = "512-81-04273";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 18, 14, 30);

    @Mock
    SellerApplicationRepository sellerApplicationRepository;

    @InjectMocks
    SellerApplicationDuplicateValidator validator;

    @Test
    void 진행_중이거나_승인된_신청이_없고_사업자등록번호도_겹치지_않으면_예외가_발생하지_않는다() {
        // given
        // 조회는 stub하지 않으면 빈 Optional과 false를 반환한다

        // when & then
        assertThatCode(() -> validator.validate(MEMBER_ID, BUSINESS_NUMBER)).doesNotThrowAnyException();
    }

    @Test
    void 심사_중인_신청이_있으면_SELLER_APPLICATION_IN_PROGRESS가_발생한다() {
        // given
        SellerApplication inReview = application();
        inReview.startReview(AT);
        given(sellerApplicationRepository.findFirstByMemberIdAndStatusIn(eq(MEMBER_ID), anyCollection()))
                .willReturn(Optional.of(inReview));

        // when & then
        assertThatThrownBy(() -> validator.validate(MEMBER_ID, BUSINESS_NUMBER))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.SELLER_APPLICATION_IN_PROGRESS));
    }

    @Test
    void 이미_승인된_회원이면_ALREADY_SELLER가_발생한다() {
        // given
        SellerApplication approved = application();
        approved.startReview(AT);
        approved.approve(REVIEWER_ID, AT);
        given(sellerApplicationRepository.findFirstByMemberIdAndStatusIn(eq(MEMBER_ID), anyCollection()))
                .willReturn(Optional.of(approved));

        // when & then
        assertThatThrownBy(() -> validator.validate(MEMBER_ID, BUSINESS_NUMBER))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.ALREADY_SELLER));
    }

    @Test
    void 다른_진행_중_신청이_같은_사업자등록번호를_쓰면_DUPLICATE_BUSINESS_NUMBER가_발생한다() {
        // given
        given(sellerApplicationRepository.existsByBusinessNumberAndStatusIn(eq(BUSINESS_NUMBER), anyCollection()))
                .willReturn(true);

        // when & then
        assertThatThrownBy(() -> validator.validate(MEMBER_ID, BUSINESS_NUMBER))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.DUPLICATE_BUSINESS_NUMBER));
    }

    @Test
    void 회원_unique_제약_위반은_진행_중_신청_에러로_바꾼다() {
        // when
        RuntimeException sut = validator.toDuplicateException(violation("uk_seller_application_active_member_id"));

        // then
        assertThat(sut).isInstanceOfSatisfying(RestApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.SELLER_APPLICATION_IN_PROGRESS));
    }

    @Test
    void 사업자등록번호_unique_제약_위반은_사업자등록번호_중복_에러로_바꾼다() {
        // when
        RuntimeException sut = validator.toDuplicateException(violation("uk_seller_application_active_business_number"));

        // then
        assertThat(sut).isInstanceOfSatisfying(RestApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.DUPLICATE_BUSINESS_NUMBER));
    }

    @Test
    void unique_제약이_아닌_무결성_위반은_원래_예외를_그대로_반환한다() {
        // given
        DataIntegrityViolationException violation = violation("fk_seller_application_member_id");

        // when
        RuntimeException sut = validator.toDuplicateException(violation);

        // then
        assertThat(sut).isSameAs(violation);
    }

    private static SellerApplication application() {
        return SellerApplication.apply(MEMBER_ID, "청송 햇살농원", "김영수", BUSINESS_NUMBER,
                "제2026-경북청송-0031호", AT);
    }

    private static DataIntegrityViolationException violation(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLIntegrityConstraintViolationException("Duplicate entry for key '" + constraintName + "'"));
    }

}
