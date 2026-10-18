package com.ok.member.seller.domain;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.exception.SellerErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class SellerApplicationTest {

    private static final LocalDateTime APPLIED_AT = LocalDateTime.of(2026, 10, 18, 14, 0);
    private static final LocalDateTime REVIEW_STARTED_AT = LocalDateTime.of(2026, 10, 19, 9, 0);
    private static final LocalDateTime DECIDED_AT = LocalDateTime.of(2026, 10, 20, 11, 0);
    private static final Long REVIEWER_ID = 100L;
    private static final String REJECT_REASON = "통신판매업 신고번호를 확인할 수 없습니다.";

    @Test
    void 신청하면_심사_대기_상태로_신청_일시를_기록한다() {
        // when
        SellerApplication sut = apply();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(SellerApplicationStatus.PENDING);
            softly.assertThat(sut.getAppliedAt()).isEqualTo(APPLIED_AT);
            softly.assertThat(sut.getReviewStartedAt()).isNull();
            softly.assertThat(sut.getApprovedAt()).isNull();
            softly.assertThat(sut.getRejectedAt()).isNull();
            softly.assertThat(sut.getRejectReason()).isNull();
            softly.assertThat(sut.getReviewerId()).isNull();
        });
    }

    @Test
    void 회원_id가_없으면_신청을_만들지_않는다() {
        // when & then
        assertThatThrownBy(() -> SellerApplication.apply(
                null, "청송 햇살농원", "김영수", "512-81-04273", "제2026-경북청송-0031호", APPLIED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("memberId는 필수입니다.");
    }

    @ParameterizedTest
    @MethodSource("잘못된_신청_항목")
    void 상호명_대표자명_통신판매업_신고번호가_비었거나_너무_길면_신청을_만들지_않는다(
            String storeName, String representativeName, String mailOrderNumber, String expectedDetail) {
        // when & then
        assertThatThrownBy(() -> SellerApplication.apply(
                1L, storeName, representativeName, "512-81-04273", mailOrderNumber, APPLIED_AT))
                .isInstanceOfSatisfying(RestApiException.class, e -> assertSoftly(softly -> {
                    softly.assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_SELLER_APPLICATION_FIELD);
                    softly.assertThat(e.getMessage()).isEqualTo(expectedDetail);
                }));
    }

    static Stream<Arguments> 잘못된_신청_항목() {
        String tooLong = "가".repeat(51);
        return Stream.of(
                Arguments.of(" ", "김영수", "제2026-경북청송-0031호", "상호명은 필수입니다."),
                Arguments.of(tooLong, "김영수", "제2026-경북청송-0031호", "상호명은 50자 이하여야 합니다."),
                Arguments.of("청송 햇살농원", null, "제2026-경북청송-0031호", "대표자명은 필수입니다."),
                Arguments.of("청송 햇살농원", tooLong, "제2026-경북청송-0031호", "대표자명은 50자 이하여야 합니다."),
                Arguments.of("청송 햇살농원", "김영수", "", "통신판매업 신고번호는 필수입니다."),
                Arguments.of("청송 햇살농원", "김영수", tooLong, "통신판매업 신고번호는 50자 이하여야 합니다.")
        );
    }

    @ParameterizedTest
    @MethodSource("정규화되지_않은_사업자등록번호")
    void 사업자등록번호가_000_00_00000_형식이_아니면_신청을_만들지_않는다(String businessNumber) {
        // when & then
        assertThatThrownBy(() -> SellerApplication.apply(
                1L, "청송 햇살농원", "김영수", businessNumber, "제2026-경북청송-0031호", APPLIED_AT))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_BUSINESS_NUMBER));
    }

    static Stream<String> 정규화되지_않은_사업자등록번호() {
        // 하이픈 없는 값도 막는다. 정규화 전 값이 저장되면 같은 번호의 중복 신청을 막지 못한다
        return Stream.of("5128104273", "512-81-0427", "512-81-0427a", null);
    }

    @Test
    void 심사를_시작한_신청을_승인하면_심사_시작일과_승인일과_심사자가_남는다() {
        // given
        SellerApplication sut = apply();

        // when
        sut.startReview(REVIEW_STARTED_AT);
        sut.approve(REVIEWER_ID, DECIDED_AT);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(SellerApplicationStatus.APPROVED);
            softly.assertThat(sut.getReviewStartedAt()).isEqualTo(REVIEW_STARTED_AT);
            softly.assertThat(sut.getApprovedAt()).isEqualTo(DECIDED_AT);
            softly.assertThat(sut.getReviewerId()).isEqualTo(REVIEWER_ID);
            softly.assertThat(sut.isApproved()).isTrue();
        });
    }

    @Test
    void 심사를_시작하지_않은_신청은_승인할_수_없다() {
        // given
        SellerApplication application = apply();

        // when & then
        assertThatThrownBy(() -> application.approve(REVIEWER_ID, DECIDED_AT))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_SELLER_APPLICATION_STATUS));
    }

    @Test
    void 이미_심사를_시작한_신청은_다시_심사를_시작할_수_없다() {
        // given
        SellerApplication application = apply();
        application.startReview(REVIEW_STARTED_AT);

        // when & then
        assertThatThrownBy(() -> application.startReview(DECIDED_AT))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_SELLER_APPLICATION_STATUS));
    }

    @Test
    void 심사_중인_신청을_거절하면_거절_일시와_사유와_심사자가_남는다() {
        // given
        SellerApplication sut = apply();
        sut.startReview(REVIEW_STARTED_AT);

        // when
        sut.reject(REVIEWER_ID, REJECT_REASON, DECIDED_AT);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(SellerApplicationStatus.REJECTED);
            softly.assertThat(sut.getRejectedAt()).isEqualTo(DECIDED_AT);
            softly.assertThat(sut.getRejectReason()).isEqualTo(REJECT_REASON);
            softly.assertThat(sut.getReviewerId()).isEqualTo(REVIEWER_ID);
            softly.assertThat(sut.getApprovedAt()).isNull();
        });
    }

    @ParameterizedTest
    @MethodSource("비어_있는_거절_사유")
    void 거절_사유가_없으면_거절하지_않는다(String reason) {
        // given
        SellerApplication application = apply();

        // when & then
        assertThatThrownBy(() -> application.reject(REVIEWER_ID, reason, DECIDED_AT))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_REJECT_REASON));
        assertThat(application.getStatus()).isEqualTo(SellerApplicationStatus.PENDING);
    }

    static Stream<String> 비어_있는_거절_사유() {
        return Stream.of("", " ", null);
    }

    @Test
    void 거절_사유가_500자를_넘으면_거절하지_않는다() {
        // given
        SellerApplication application = apply();

        // when & then
        assertThatThrownBy(() -> application.reject(REVIEWER_ID, "가".repeat(501), DECIDED_AT))
                .isInstanceOfSatisfying(RestApiException.class, e -> assertSoftly(softly -> {
                    softly.assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_REJECT_REASON);
                    softly.assertThat(e.getMessage()).isEqualTo("거절 사유는 500자 이하여야 합니다.");
                }));
    }

    @Test
    void 심사자_id가_없으면_승인하지_않는다() {
        // given
        SellerApplication application = apply();
        application.startReview(REVIEW_STARTED_AT);

        // when & then
        assertThatThrownBy(() -> application.approve(null, DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reviewerId는 필수입니다.");
    }

    @Test
    void 승인된_신청은_거절할_수_없다() {
        // given
        SellerApplication application = apply();
        application.startReview(REVIEW_STARTED_AT);
        application.approve(REVIEWER_ID, DECIDED_AT);

        // when & then
        assertThatThrownBy(() -> application.reject(REVIEWER_ID, REJECT_REASON, DECIDED_AT))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_SELLER_APPLICATION_STATUS));
    }

    @Test
    void 거절을_뺀_상태가_새_신청을_막는다() {
        // when
        Set<SellerApplicationStatus> sut = SellerApplicationStatus.activeStatuses();

        // then
        assertThat(sut).containsExactlyInAnyOrder(
                SellerApplicationStatus.PENDING, SellerApplicationStatus.IN_REVIEW, SellerApplicationStatus.APPROVED);
    }

    private static SellerApplication apply() {
        return SellerApplication.apply(1L, "청송 햇살농원", "김영수", "512-81-04273", "제2026-경북청송-0031호", APPLIED_AT);
    }

}
