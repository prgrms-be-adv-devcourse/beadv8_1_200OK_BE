package com.ok.payout.domain;

import com.ok.payout.PayoutSchema;
import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.payout.exception.PayoutErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import static jakarta.persistence.GenerationType.IDENTITY;

@Entity
@Immutable
@Table(name = "payout_candidate_items", catalog = PayoutSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutCandidateItem extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PayoutEventType eventType;

    // 주문 ID. 배송비 후보는 (주문, 판매자) 단위라 주문 ID로 묶음
    @Column(nullable = false)
    private Long orderId;

    // 주문상품 ID. 배송비 행은 null (배송비는 상품이 아니라 판매자 묶음 배송 1건의 금액, DB CHECK가 보장)
    private Long orderItemId;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false)
    private Long amount;

    // 관리자 조정이 없는 평소 이벤트(구매 확정 등)의 조정 번호. NULL은 UK 중복 검사가 안 되므로 0으로 저장
    public static final long NO_ADMIN_ADJUSTMENT_ID = 0L;

    // 조정 사유 최대 길이 (DB reason VARCHAR(255)와 맞춤)
    public static final int MAX_REASON_LENGTH = 255;

    // 0이면 평소 이벤트, 0보다 크면 관리자 조정 요청 ID
    @Column(nullable = false)
    private Long adjustmentId;

    // 수동 조정 시에만 사용
    private Long adminId;

    @Column(length = MAX_REASON_LENGTH)
    private String reason;

    // 검증 없이 값만 채움 (검증은 각 생성 메서드에서)
    private static PayoutCandidateItem create(PayoutEventType eventType, Long orderId, Long orderItemId, Long sellerId,
                                             Long amount, long adjustmentId, Long adminId, String reason) {
        PayoutCandidateItem item = new PayoutCandidateItem();
        item.eventType = eventType;
        item.orderId = orderId;
        item.orderItemId = orderItemId;
        item.sellerId = sellerId;
        item.amount = amount;
        item.adjustmentId = adjustmentId;
        item.adminId = adminId;
        item.reason = reason;
        return item;
    }

    public static PayoutCandidateItem ofSaleFee(Long orderId, Long orderItemId, Long sellerId, Long amount) {
        return create(PayoutEventType.SALE_FEE, orderId, orderItemId, sellerId, amount, NO_ADMIN_ADJUSTMENT_ID, null, null);
    }

    public static PayoutCandidateItem ofSaleAmount(Long orderId, Long orderItemId, Long sellerId, Long amount) {
        return create(PayoutEventType.SALE_AMOUNT, orderId, orderItemId, sellerId, amount, NO_ADMIN_ADJUSTMENT_ID, null, null);
    }

    // 배송비는 주문상품이 아니라 (주문, 판매자) 단위라 주문상품 ID를 받지 않음 (null로 저장)
    public static PayoutCandidateItem ofSaleShippingFee(Long orderId, Long sellerId, Long amount) {
        return create(PayoutEventType.SALE_SHIPPING_FEE, orderId, null, sellerId, amount, NO_ADMIN_ADJUSTMENT_ID, null, null);
    }

    // 관리자 수동 조정 (오류 수정용, 원래 정산후보와 같은 eventType). 음수 금액 허용
    // 조정 번호는 관리자 조정 요청 ID라 1 이상만 허용 (0은 평소 이벤트 전용, null·0은 호출 쪽 버그)
    // 배송비 조정이면 orderItemId는 null (원래 배송비 후보와 같은 (주문, 판매자) 단위)
    public static PayoutCandidateItem manualAdjustment(PayoutEventType eventType, Long orderId, Long orderItemId, Long sellerId,
                                                       Long amount, Long adjustmentId, Long adminId, String reason) {
        if (adjustmentId == null || adjustmentId <= NO_ADMIN_ADJUSTMENT_ID) {
            throw new RestApiException(PayoutErrorCode.INVALID_ADJUSTMENT_ID);
        }
        validateManualAdjustment(adminId, reason);
        return create(eventType, orderId, orderItemId, sellerId, amount, adjustmentId, adminId, reason);
    }

    private static void validateManualAdjustment(Long adminId, String reason) {
        if (adminId == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_ADMIN_ID);
        }
        if (reason == null || reason.isBlank()) {
            throw new RestApiException(PayoutErrorCode.MISSING_ADJUSTMENT_REASON);
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new RestApiException(PayoutErrorCode.ADJUSTMENT_REASON_TOO_LONG);
        }
    }

    // 시스템(플랫폼) 몫 여부: SALE_FEE는 시스템, 나머지는 판매자. 이벤트 타입이 추가되면 함께 수정
    // 같은 기준이 PayoutQueryRepository.candidateSearchCondition()에도 있음 (판매자 정산후보 조회에서 수수료 제외) → 함께 수정
    // PayoutDraftValidator.checkDraftMatchesSnapshot()에도 있음 (결과 검증의 몫 구분 확인, #290) → 함께 수정
    public boolean isSystemShare() {
        return eventType == PayoutEventType.SALE_FEE;
    }
}
