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

    // 진행 중일 때만 값이 있다. UNIQUE라 한 주문 상품에 진행 중인 요청을 두 건 받지 않는다.
    // 끝 상태가 되면 비워 같은 상품에 새 요청을 받는다(MySQL UNIQUE는 NULL을 여러 개 허용). 비우는 일은 승인·거절·철회(#409)에서 한다
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
}
