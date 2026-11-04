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
 * 주문·상품·회원은 다른 모듈이라 ID 값으로만 들고 있다.
 * 단가·판매자·옵션·이름은 요청 때 주문에서 받아 저장해 둔다. 주문 쪽 값이 나중에 바뀌어도 환불 금액이 흔들리지 않게 하려는 것이다.
 */
@Entity
@Getter
@Table(name = "refund", catalog = RefundSchema.NAME)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends BaseTime {

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

    // 요청 단계에서는 쓰지 않지만 재고를 되돌릴 때(#414) 필요하다. 주문 시점 값이라 요청 때 받아 두지 않으면 나중에 채울 수 없다
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

    // 판매자가 승인·거절을 판단할 참고용이라 선택 입력이다. 안 적은 요청이 있어 NULL을 허용한다
    @Column(name = "cancel_reason")
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status;

    // 거절일 때만 값이 있다. 요청됨·처리 중·철회에는 거절 사유가 없는 게 맞아 NULL을 허용한다
    @Column(name = "reject_reason")
    private String rejectReason;

    // 판매자가 승인·거절하거나 구매자가 철회한 시각. 요청됨인 동안에는 아직 정해지지 않아 NULL이다
    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    // 요청됨·처리 중일 때만 값이 있다. UNIQUE라 한 주문 상품에 진행 중인 요청을 두 건 받지 않는다.
    // 끝 상태(거절·철회)가 되면 비워 같은 상품에 새 요청을 받는다(MySQL UNIQUE는 NULL을 여러 개 허용)
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
            long unitPrice,
            String cancelReason
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
        this.cancelReason = cancelReason;
        this.status = RefundStatus.REQUESTED;
        this.activeOrderItemId = orderItemId;
    }

    /**
     * 구매자의 취소 요청을 "요청됨"으로 만든다.
     *
     * @param unitPrice    주문 시점 단가 (원)
     * @param cancelReason 구매자가 적은 취소 사유. 안 적었으면 {@code null}
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
            long unitPrice,
            String cancelReason
    ) {
        return new Refund(orderId, orderItemId, quantity, buyerId, sellerId, productOptionId, productName, optionName,
                unitPrice, cancelReason);
    }

    /**
     * 판매자 승인으로 처리를 시작한다. {@code REQUESTED → PROCESSING}
     * 아직 끝난 게 아니라 진행 중 칸은 그대로 둔다. 상품 금액은 요청 때 저장한 값을 그대로 쓰고, 배송비는 #410에서 이때 정한다.
     *
     * @throws RestApiException 요청됨이 아니면 {@code INVALID_REFUND_STATE}
     */
    public void approve(LocalDateTime now) {
        requireStatus(RefundStatus.REQUESTED);
        this.decidedAt = now;
        this.status = RefundStatus.PROCESSING;
    }

    /**
     * 판매자가 거절한다. {@code REQUESTED → REJECTED}
     * 사유는 구매자에게 보여 주므로 꼭 받는다. 형식(길이, 앞뒤 공백)은 요청 DTO가 먼저 정리한다.
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
     * 구매자가 승인 전에 요청을 거둔다. {@code REQUESTED → WITHDRAWN}
     * 승인한 뒤에는 배송·돈 처리가 이어지므로 철회를 받지 않는다.
     *
     * @throws RestApiException 요청됨이 아니면 {@code INVALID_REFUND_STATE}
     */
    public void withdraw(LocalDateTime now) {
        requireStatus(RefundStatus.REQUESTED);
        this.decidedAt = now;
        finish(RefundStatus.WITHDRAWN);
    }

    public boolean isOwnedByBuyer(Long memberId) {
        return buyerId.equals(memberId);
    }

    public boolean isOwnedBySeller(Long memberId) {
        return sellerId.equals(memberId);
    }

    // 끝 상태로 바꾸면서 진행 중 칸을 비운다. 같은 트랜잭션에서 비워야 커밋 직후 같은 상품의 새 요청이 UNIQUE에 걸리지 않는다
    private void finish(RefundStatus finishedStatus) {
        this.status = finishedStatus;
        this.activeOrderItemId = null;
    }

    private void requireStatus(RefundStatus expected) {
        if (this.status != expected) {
            throw new RestApiException(RefundErrorCode.INVALID_REFUND_STATE);
        }
    }
}
