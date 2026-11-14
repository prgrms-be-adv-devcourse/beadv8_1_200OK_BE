package com.ok.payout.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.payout.PayoutSchema;
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

import java.time.LocalDateTime;

import static jakarta.persistence.GenerationType.IDENTITY;

// 막힌 주문의 원인 기록 1줄 (#582, 막힌 주문 집계 표의 하위 표)
// 해결 안 된 실패 기록 중 함께 보류(ORDER_HELD·"[함께 보류]" 상세)를 빼고, 주문상품·종류·사유·상세가 같은 기록은 1번만 남긴 것
//   멈춘 주문은 같은 실패가 실행마다 쌓이므로 그대로 두면 같은 줄이 실행 수만큼 반복됨
// 막힌 주문 표와 같이 예외로 수정·삭제를 허용함: 다시 판정할 때 그 주문의 원인을 지우고 다시 넣음
// 범주는 저장하지 않음 (사유가 들고 있음, PayoutFailureReason.category)
@Entity
@Table(name = "payout_blocked_order_cause", catalog = PayoutSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutBlockedOrderCause extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    // 막힌 주문 ID (payout_blocked_order.order_id, DB 외래키)
    @Column(nullable = false)
    private Long orderId;

    // 배송비 후보는 null
    private Long orderItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PayoutEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PayoutFailureReason reason;

    @Column(nullable = false, length = PayoutFailure.MAX_DETAIL_LENGTH)
    private String detail;

    @Column(nullable = false)
    private Long sellerId;

    // 같은 원인 기록 중 가장 늦은 실패 시각
    @Column(nullable = false)
    private LocalDateTime failedAt;

    public static PayoutBlockedOrderCause of(Long orderId, Long orderItemId, PayoutEventType eventType,
                                             PayoutFailureReason reason, String detail, Long sellerId,
                                             LocalDateTime failedAt) {
        if (orderId == null || eventType == null || reason == null || detail == null || sellerId == null
                || failedAt == null) {
            throw new IllegalArgumentException("막힌 주문 원인에는 주문 ID, 종류, 사유, 상세, 판매자 ID, 실패 시각이 필요합니다.");
        }
        PayoutBlockedOrderCause cause = new PayoutBlockedOrderCause();
        cause.orderId = orderId;
        cause.orderItemId = orderItemId;
        cause.eventType = eventType;
        cause.reason = reason;
        cause.detail = detail;
        cause.sellerId = sellerId;
        cause.failedAt = failedAt;
        return cause;
    }
}
