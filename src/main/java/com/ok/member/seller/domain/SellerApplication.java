package com.ok.member.seller.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.member.seller.exception.SellerErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * 회원의 판매자 등록 신청 한 건을 나타낸다.
 * 거절되면 이력으로 남고 회원은 새 신청을 만든다. 그래서 한 회원이 여러 건을 가질 수 있지만,
 * 거절되지 않은 신청(진행 중·승인)은 회원당·사업자등록번호당 하나만 있다(DB 유니크 제약으로 보장).
 */
@Entity
@Table(name = "seller_application")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerApplication extends BaseTime {

    // 컬럼 길이와 생성 시 검증이 같은 값을 쓰도록 상수로 둔다. 바꾸면 마이그레이션도 함께 바꾼다
    private static final int NAME_MAX_LENGTH = 50;
    private static final int MAIL_ORDER_NUMBER_MAX_LENGTH = 50;
    private static final int REJECT_REASON_MAX_LENGTH = 500;

    // 요청 DTO가 정규화한 형식이다. DTO를 거치지 않고 만들어도 같은 형식만 저장되어야 중복 검사가 맞는다
    private static final Pattern BUSINESS_NUMBER = Pattern.compile("^\\d{3}-\\d{2}-\\d{5}$");

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "store_name", nullable = false, length = NAME_MAX_LENGTH)
    private String storeName;

    @Column(name = "representative_name", nullable = false, length = NAME_MAX_LENGTH)
    private String representativeName;

    @Column(name = "business_number", nullable = false, length = 12)
    private String businessNumber;

    @Column(name = "mail_order_number", nullable = false, length = MAIL_ORDER_NUMBER_MAX_LENGTH)
    private String mailOrderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SellerApplicationStatus status;

    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;

    @Column(name = "review_started_at")
    private LocalDateTime reviewStartedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "reject_reason", length = REJECT_REASON_MAX_LENGTH)
    private String rejectReason;

    // 승인·거절을 결정한 관리자. 감사 추적용으로, 결정 전에는 null이다
    @Column(name = "reviewer_id")
    private Long reviewerId;

    // 관리자 두 명이 같은 신청을 동시에 처리하면 나중에 커밋하는 쪽이 낙관적 락 예외로 실패한다
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    private SellerApplication(Long memberId, String storeName, String representativeName,
                              String businessNumber, String mailOrderNumber, LocalDateTime appliedAt) {
        // 회원 id와 신청 일시는 토큰과 서버 시계에서 오는 값이라 사용자 입력 오류가 아니다
        if (memberId == null) {
            throw new IllegalArgumentException("memberId는 필수입니다.");
        }
        if (appliedAt == null) {
            throw new IllegalArgumentException("appliedAt은 필수입니다.");
        }
        this.memberId = memberId;
        this.storeName = requireText(storeName, NAME_MAX_LENGTH,
                "상호명은 필수입니다.", "상호명은 50자 이하여야 합니다.");
        this.representativeName = requireText(representativeName, NAME_MAX_LENGTH,
                "대표자명은 필수입니다.", "대표자명은 50자 이하여야 합니다.");
        this.businessNumber = requireBusinessNumber(businessNumber);
        this.mailOrderNumber = requireText(mailOrderNumber, MAIL_ORDER_NUMBER_MAX_LENGTH,
                "통신판매업 신고번호는 필수입니다.", "통신판매업 신고번호는 50자 이하여야 합니다.");
        this.status = SellerApplicationStatus.PENDING;
        this.appliedAt = appliedAt;
    }

    /**
     * 심사 대기 상태의 판매자 신청을 만든다.
     * 같은 회원·사업자등록번호의 진행 중 신청이 없는지는 호출하는 쪽(서비스)이 확인한다.
     *
     * <p>요청 DTO가 먼저 같은 규칙으로 422를 낸다. 이 검증은 DTO를 거치지 않는 경로(관리자 기능, 데이터 이관)를 위한 마지막 방어선이다.
     *
     * @param businessNumber 정규화된 사업자등록번호 ({@code 000-00-00000})
     * @throws RestApiException 상호명·대표자명·통신판매업 신고번호가 비었거나 너무 길면 {@code INVALID_SELLER_APPLICATION_FIELD},
     *                          사업자등록번호 형식이 틀리면 {@code INVALID_BUSINESS_NUMBER}
     */
    public static SellerApplication apply(Long memberId, String storeName, String representativeName,
                                          String businessNumber, String mailOrderNumber, LocalDateTime appliedAt) {
        return new SellerApplication(memberId, storeName, representativeName,
                businessNumber, mailOrderNumber, appliedAt);
    }

    /**
     * 심사를 시작한다. 관리자 API에서 호출한다.
     *
     * @throws RestApiException 심사 대기 상태가 아니면 {@code INVALID_SELLER_APPLICATION_STATUS}
     */
    public void startReview(LocalDateTime startedAt) {
        requireStatus(SellerApplicationStatus.PENDING);
        this.status = SellerApplicationStatus.IN_REVIEW;
        this.reviewStartedAt = startedAt;
    }

    /**
     * 신청을 승인한다. 관리자 API에서 호출한다.
     * 신청 내역에 심사 시작일과 승인일을 모두 보여 주므로, 심사를 시작하지 않은 신청은 바로 승인하지 않는다.
     *
     * @param reviewerId 승인을 결정한 관리자 회원 id
     * @throws RestApiException 심사 중이 아니면 {@code INVALID_SELLER_APPLICATION_STATUS}
     */
    public void approve(Long reviewerId, LocalDateTime approvedAt) {
        requireReviewer(reviewerId);
        requireStatus(SellerApplicationStatus.IN_REVIEW);
        this.status = SellerApplicationStatus.APPROVED;
        this.approvedAt = approvedAt;
        this.reviewerId = reviewerId;
    }

    /**
     * 신청을 거절한다. 관리자 API에서 호출한다.
     * 거절된 회원이 무엇을 고쳐 다시 신청할지 알 수 있도록 사유를 반드시 남긴다.
     * 거절하면 같은 회원·사업자등록번호로 바로 다시 신청할 수 있다.
     *
     * @param reviewerId 거절을 결정한 관리자 회원 id
     * @param reason     거절 사유. 신청한 회원에게 그대로 보인다
     * @throws RestApiException 사유가 비었거나 500자를 넘으면 {@code INVALID_REJECT_REASON},
     *                          심사 대기·심사 중이 아니면 {@code INVALID_SELLER_APPLICATION_STATUS}
     */
    public void reject(Long reviewerId, String reason, LocalDateTime rejectedAt) {
        requireReviewer(reviewerId);
        // 사유는 관리자가 입력하는 값이라 사용자 입력 오류(422)로 돌려준다
        if (reason == null || reason.isBlank()) {
            throw new RestApiException(SellerErrorCode.INVALID_REJECT_REASON, "거절 사유를 입력해야 합니다.");
        }
        if (reason.length() > REJECT_REASON_MAX_LENGTH) {
            throw new RestApiException(SellerErrorCode.INVALID_REJECT_REASON, "거절 사유는 500자 이하여야 합니다.");
        }
        if (status != SellerApplicationStatus.PENDING && status != SellerApplicationStatus.IN_REVIEW) {
            throw new RestApiException(SellerErrorCode.INVALID_SELLER_APPLICATION_STATUS);
        }
        this.status = SellerApplicationStatus.REJECTED;
        this.rejectedAt = rejectedAt;
        this.rejectReason = reason;
        this.reviewerId = reviewerId;
    }

    public boolean isApproved() {
        return status == SellerApplicationStatus.APPROVED;
    }

    private static String requireText(String value, int maxLength, String missingMessage, String tooLongMessage) {
        if (value == null || value.isBlank()) {
            throw new RestApiException(SellerErrorCode.INVALID_SELLER_APPLICATION_FIELD, missingMessage);
        }
        if (value.length() > maxLength) {
            throw new RestApiException(SellerErrorCode.INVALID_SELLER_APPLICATION_FIELD, tooLongMessage);
        }
        return value;
    }

    private static String requireBusinessNumber(String businessNumber) {
        if (businessNumber == null || !BUSINESS_NUMBER.matcher(businessNumber).matches()) {
            throw new RestApiException(SellerErrorCode.INVALID_BUSINESS_NUMBER);
        }
        return businessNumber;
    }

    // 관리자 id는 토큰에서 오는 값이라 사용자 입력 오류가 아니다
    private static void requireReviewer(Long reviewerId) {
        if (reviewerId == null) {
            throw new IllegalArgumentException("reviewerId는 필수입니다.");
        }
    }

    private void requireStatus(SellerApplicationStatus expected) {
        if (status != expected) {
            throw new RestApiException(SellerErrorCode.INVALID_SELLER_APPLICATION_STATUS);
        }
    }

}
