package com.ok.refund.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.refund.RefundSchema;
import com.ok.refund.exception.RefundErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 환불 요청 한 건. 주문 상품 한 줄을 개수 단위로 취소한다(1단계: 출고 전 취소).
 *
 * <p>상태 전이는 {@link RefundStatus}를 따른다. 주문·상품·배송·지갑은 다른 모듈이라 ID 값으로만 들고 있다.
 * 단가·판매자·옵션·이름은 요청 때 주문에서 받아 저장해 두고, 배송비·추가 운임·뺄 배송비는 승인할 때 한 번 계산해 저장한다.
 */
@Entity
@Getter
@Table(name = "refund", catalog = RefundSchema.NAME)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends BaseTime {

    /** 처리 중 실패 코드 칸의 길이. 넘는 값은 잘라 저장한다 */
    private static final int FAILURE_CODE_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "product_option_id", nullable = false)
    private Long productOptionId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "option_name", nullable = false)
    private String optionName;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(name = "item_amount", nullable = false)
    private long itemAmount;

    @Column(name = "shipping_fee_amount", nullable = false)
    private long shippingFeeAmount;

    @Column(name = "remote_area_fee_amount", nullable = false)
    private long remoteAreaFeeAmount;

    // 일부 취소로 무료배송 기준이 깨져 환불에서 뺀 기본 배송비. 주문에 알려 구매확정 때 판매자 몫 배송비로 보내게 하고,
    // 같은 묶음이 나중에 전부 취소되면 마지막 요청이 이 합을 돌려준다
    @Column(name = "deducted_shipping_fee_amount", nullable = false)
    private long deductedShippingFeeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "failed_step", length = 30)
    private RefundFailedStep failedStep;

    @Column(name = "failure_code", length = FAILURE_CODE_MAX_LENGTH)
    private String failureCode;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // 요청됨·처리 중일 때만 값이 있다. UNIQUE라 한 주문 상품에 진행 중인 요청을 두 건 받지 않고,
    // 끝 상태가 되면 비워 같은 상품에 새 요청을 받는다(MySQL UNIQUE는 NULL을 여러 개 허용)
    @Column(name = "active_order_item_id")
    private Long activeOrderItemId;

    private Refund(
            Long orderId,
            Long orderItemId,
            int quantity,
            Long buyerId,
            Long sellerId,
            Long productOptionId,
            String productName,
            String optionName,
            long unitPrice
    ) {
        // 개수는 구매자 입력이라 에러 코드로, 나머지는 주문 모듈에서 받은 값이라 표준 예외로 거른다(exception.md)
        if (quantity < 1) {
            throw new RestApiException(RefundErrorCode.INVALID_REFUND_QUANTITY);
        }
        if (orderId == null || orderItemId == null || buyerId == null || sellerId == null || productOptionId == null) {
            throw new IllegalArgumentException("주문·주문 상품·구매자·판매자·옵션 ID는 필수입니다.");
        }
        if (productName == null || optionName == null) {
            throw new IllegalArgumentException("상품명과 옵션명은 필수입니다.");
        }
        if (unitPrice < 0) {
            throw new IllegalArgumentException("단가는 0 이상이어야 합니다.");
        }
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.quantity = quantity;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.productOptionId = productOptionId;
        this.productName = productName;
        this.optionName = optionName;
        this.unitPrice = unitPrice;
        // 단가 x 개수가 long을 넘으면 조용히 음수가 되지 않게 예외로 막는다
        this.itemAmount = Math.multiplyExact(unitPrice, quantity);
        this.shippingFeeAmount = 0L;
        this.remoteAreaFeeAmount = 0L;
        this.deductedShippingFeeAmount = 0L;
        this.status = RefundStatus.REQUESTED;
        this.activeOrderItemId = orderItemId;
    }

    /**
     * 구매자의 취소 요청을 "요청됨"으로 만든다.
     * 배송비·추가 운임은 0으로 시작하고, 판매자가 승인할 때 묶음 전부 취소이면 채운다.
     *
     * @param unitPrice 주문 시점 단가 (원)
     * @throws RestApiException 개수가 1 미만이면 {@code INVALID_REFUND_QUANTITY}
     */
    public static Refund request(
            Long orderId,
            Long orderItemId,
            int quantity,
            Long buyerId,
            Long sellerId,
            Long productOptionId,
            String productName,
            String optionName,
            long unitPrice
    ) {
        return new Refund(orderId, orderItemId, quantity, buyerId, sellerId, productOptionId, productName, optionName, unitPrice);
    }

    /**
     * 구매자가 승인 전에 요청을 거둔다. {@code REQUESTED → WITHDRAWN}
     *
     * @throws RestApiException 요청됨이 아니면 {@code INVALID_REFUND_STATE}
     */
    public void withdraw(LocalDateTime now) {
        requireStatus(RefundStatus.REQUESTED);
        this.decidedAt = now;
        finish(RefundStatus.WITHDRAWN);
    }

    /**
     * 판매자가 거절한다. {@code REQUESTED → REJECTED}
     *
     * @throws RestApiException 사유가 비었으면 {@code REJECT_REASON_REQUIRED}, 요청됨이 아니면 {@code INVALID_REFUND_STATE}
     */
    public void reject(String reason, LocalDateTime now) {
        if (reason == null || reason.isBlank()) {
            throw new RestApiException(RefundErrorCode.REJECT_REASON_REQUIRED);
        }
        requireStatus(RefundStatus.REQUESTED);
        this.rejectReason = reason;
        this.decidedAt = now;
        finish(RefundStatus.REJECTED);
    }

    /**
     * 판매자 승인으로 처리를 시작하고 배송비 금액들을 저장한다. {@code REQUESTED → PROCESSING}
     * 금액은 이때 한 번만 정한다. 다시 처리할 때 다시 계산하면 그사이 다른 요청이 끝나 배송비 판단이 달라질 수 있고,
     * 주문·지갑에 다시 보낼 때도 같은 값을 보내야 받는 쪽의 환불ID 중복 확인과 맞는다.
     *
     * @param fee {@link RefundShippingFeePolicy}가 정한 돌려줄 배송비·추가 운임과 뺄 배송비
     * @throws RestApiException 요청됨이 아니면 {@code INVALID_REFUND_STATE}
     */
    public void startProcessing(RefundShippingFee fee, LocalDateTime now) {
        if (fee.shippingFee() < 0 || fee.remoteAreaFee() < 0 || fee.deductedShippingFee() < 0) {
            throw new IllegalArgumentException("배송비, 추가 운임, 뺄 배송비는 0 이상이어야 합니다.");
        }
        // 정책은 묶음 전부 취소(돌려줌)와 기준이 깨지는 일부 취소(뺌) 중 하나만 고른다. 둘 다면 계산 버그다
        if (fee.deductedShippingFee() > 0 && (fee.shippingFee() > 0 || fee.remoteAreaFee() > 0)) {
            throw new IllegalArgumentException("배송비를 돌려주면서 동시에 뺄 수 없습니다.");
        }
        // 취소 금액보다 많이 빼면 환불 금액이 음수가 된다. 정책이 먼저 막으므로 여기 오면 계산 버그다
        if (fee.deductedShippingFee() > itemAmount) {
            throw new IllegalArgumentException("뺄 배송비가 상품 금액보다 클 수 없습니다.");
        }
        requireStatus(RefundStatus.REQUESTED);
        this.shippingFeeAmount = fee.shippingFee();
        this.remoteAreaFeeAmount = fee.remoteAreaFee();
        this.deductedShippingFeeAmount = fee.deductedShippingFee();
        this.decidedAt = now;
        this.status = RefundStatus.PROCESSING;
    }

    /**
     * 배송 잠금이 거절되어(이미 출고 등) 취소할 수 없음으로 끝낸다. {@code PROCESSING → NOT_APPROVABLE}
     * 저장해 둔 배송비 판단은 그대로 둔다. 잠금이 거절됐다는 건 같은 묶음 전체가 출고됐다는 뜻이라 같은 묶음의 다른 요청도 배송비를 받지 못한다.
     *
     * @throws RestApiException 처리 중이 아니면 {@code INVALID_REFUND_STATE}
     */
    public void markNotApprovable(LocalDateTime now) {
        requireStatus(RefundStatus.PROCESSING);
        this.completedAt = now;
        finish(RefundStatus.NOT_APPROVABLE);
    }

    /**
     * 처리 중에 멈춘 단계와 받은 오류 코드를 기록한다. 상태는 처리 중 그대로 둔다(승인 뒤에는 되돌리지 않는다).
     *
     * @throws RestApiException 처리 중이 아니면 {@code INVALID_REFUND_STATE}
     */
    public void recordFailure(RefundFailedStep step, String code) {
        if (step == null) {
            throw new IllegalArgumentException("실패 단계는 필수입니다.");
        }
        requireStatus(RefundStatus.PROCESSING);
        this.failedStep = step;
        this.failureCode = truncate(code);
    }

    /**
     * 모든 단계가 끝나 완료로 바꾼다. {@code PROCESSING → COMPLETED}
     * 앞서 기록한 실패는 지운다. 다시 처리해 끝났으면 더는 멈춘 건이 아니기 때문이다.
     *
     * @throws RestApiException 처리 중이 아니면 {@code INVALID_REFUND_STATE}
     */
    public void complete(LocalDateTime now) {
        requireStatus(RefundStatus.PROCESSING);
        this.failedStep = null;
        this.failureCode = null;
        this.completedAt = now;
        finish(RefundStatus.COMPLETED);
    }

    /**
     * 구매자에게 돌려줄 금액. 상품 금액에 (묶음 전부 취소일 때만 채워지는) 배송비와 추가 운임을 더하고,
     * (일부 취소로 무료배송 기준이 깨졌을 때만 채워지는) 뺄 배송비를 뺀다. 0원일 수 있다.
     */
    public long getRefundAmount() {
        long added = Math.addExact(Math.addExact(itemAmount, shippingFeeAmount), remoteAreaFeeAmount);
        return Math.subtractExact(added, deductedShippingFeeAmount);
    }

    public boolean isOwnedByBuyer(Long memberId) {
        return buyerId.equals(memberId);
    }

    public boolean isOwnedBySeller(Long memberId) {
        return sellerId.equals(memberId);
    }

    // 끝 상태로 바꾸면서 진행 중 칸을 비운다. 같은 트랜잭션에서 비워야 같은 상품의 새 요청이 UNIQUE에 걸리지 않는다
    private void finish(RefundStatus finishedStatus) {
        this.status = finishedStatus;
        this.activeOrderItemId = null;
    }

    private void requireStatus(RefundStatus expected) {
        if (this.status != expected) {
            throw new RestApiException(RefundErrorCode.INVALID_REFUND_STATE);
        }
    }

    private static String truncate(String code) {
        if (code == null || code.length() <= FAILURE_CODE_MAX_LENGTH) {
            return code;
        }
        return code.substring(0, FAILURE_CODE_MAX_LENGTH);
    }
}
