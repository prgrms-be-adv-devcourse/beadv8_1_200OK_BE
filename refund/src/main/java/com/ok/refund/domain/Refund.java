package com.ok.refund.domain;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 환불 요청 한 건. 주문 상품 한 줄을 개수 단위로 취소한다(1단계: 출고 전 취소).
 * 주문·상품·회원은 다른 모듈이라 ID 값으로만 들고 있다.
 * 단가·판매자·옵션·이름은 요청 때 주문에서 받아 저장해 둔다. 주문 쪽 값이 나중에 바뀌어도 환불 금액이 흔들리지 않게 하려는 것이다.
 *
 * <p>JPA를 모르는 도메인 객체다(#481). 테이블 매핑은 {@code adapter/out/persistence}의 {@code RefundJpaEntity}가 맡는다.
 * 상태를 바꾼 뒤에는 서비스가 출력 포트 {@code RefundRepository.update}를 불러야 DB에 반영된다.
 * 변경 감지에 기대지 않는 이유: 도메인을 바꾸는 순간 엔티티가 바뀌면, 그 뒤의 조회가 자동 flush로 바뀐 값을 함께 읽는 순서 규칙을
 * 코드마다 기억해야 했다. 이제 엔티티는 {@code update}를 부를 때만 바뀐다.
 */
@Getter
public class Refund {

    // 저장 전(request로 막 만든 것)에는 null이다. 저장소가 저장한 뒤 restore로 채운 새 객체를 돌려준다(setter를 두지 않으려고)
    private final Long id;
    private final Long orderId;
    private final Long orderItemId;
    private final int quantity;
    private final Long buyerId;
    private final Long sellerId;

    // 요청 단계에서는 쓰지 않지만 재고를 되돌릴 때(#414) 필요하다. 주문 시점 값이라 요청 때 받아 두지 않으면 나중에 채울 수 없다
    private final Long productOptionId;
    private final String productName;
    private final String optionName;
    private final long unitPrice;
    private final long itemAmount;

    // 배송비 세 칸은 판매자 승인 때 같은 묶음의 다른 요청을 함께 보고 정한다(#410). 요청됨·거절·철회에서는 0이다.
    // 요청 때 정하지 않는 이유: 한 묶음을 나눠 요청하면 어느 요청이 "마지막(묶음 전부 취소)"인지 승인 순서로만 알 수 있다
    private long shippingFeeAmount;
    private long remoteAreaFeeAmount;
    private long deductedShippingFeeAmount;

    // 판매자가 승인·거절을 판단할 참고용이라 선택 입력이다. 안 적은 요청이 있어 null을 허용한다
    private final String cancelReason;

    private RefundStatus status;

    // 거절일 때만 값이 있다. 요청됨·처리 중·철회에는 거절 사유가 없는 게 맞아 null을 허용한다
    private String rejectReason;

    // 판매자가 승인·거절하거나 구매자가 철회한 시각. 요청됨인 동안에는 아직 정해지지 않아 null이다
    private LocalDateTime decidedAt;

    // 처리 중에 다른 모듈 호출이 실패했을 때만 값이 있다(#412). 실패가 없는 것이 정상이라 null을 허용한다
    private RefundFailedStep failedStep;

    // 멈춘 이유. 에러 코드 이름이나 예외 클래스 이름만 둔다. 내부 메시지는 다른 모듈의 주소·응답 내용이 섞일 수 있어 남기지 않는다
    private String failureCode;

    // 요청됨·처리 중일 때만 값이 있다. DB의 UNIQUE라 한 주문 상품에 진행 중인 요청을 두 건 받지 않는다.
    // 끝 상태(거절·철회)가 되면 비워 같은 상품에 새 요청을 받는다(MySQL UNIQUE는 NULL을 여러 개 허용).
    // DB 제약을 위한 칸이지만 "진행 중인가"라는 도메인 상태라 도메인이 직접 채우고 비운다
    private Long activeOrderItemId;

    // 응답의 요청 시각(requestedAt)으로 쓰는 읽기용 칸. 저장할 때 DB 쪽(BaseTime 감사)이 채우고, 저장 전에는 null이다
    private final LocalDateTime createdAt;

    // 모든 칸을 그대로 받는 생성자. 검사는 하지 않는다. 새 요청의 검사는 request가, 저장된 값의 복원은 restore가 맡는다
    private Refund(
            Long id,
            Long orderId,
            Long orderItemId,
            int quantity,
            Long buyerId,
            Long sellerId,
            Long productOptionId,
            String productName,
            String optionName,
            long unitPrice,
            long itemAmount,
            long shippingFeeAmount,
            long remoteAreaFeeAmount,
            long deductedShippingFeeAmount,
            String cancelReason,
            RefundStatus status,
            String rejectReason,
            LocalDateTime decidedAt,
            RefundFailedStep failedStep,
            String failureCode,
            Long activeOrderItemId,
            LocalDateTime createdAt
    ) {
        this.id = id;
        this.orderId = orderId;
        this.orderItemId = orderItemId;
        this.quantity = quantity;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.productOptionId = productOptionId;
        this.productName = productName;
        this.optionName = optionName;
        this.unitPrice = unitPrice;
        this.itemAmount = itemAmount;
        this.shippingFeeAmount = shippingFeeAmount;
        this.remoteAreaFeeAmount = remoteAreaFeeAmount;
        this.deductedShippingFeeAmount = deductedShippingFeeAmount;
        this.cancelReason = cancelReason;
        this.status = status;
        this.rejectReason = rejectReason;
        this.decidedAt = decidedAt;
        this.failedStep = failedStep;
        this.failureCode = failureCode;
        this.activeOrderItemId = activeOrderItemId;
        this.createdAt = createdAt;
    }

    /**
     * 구매자의 취소 요청을 "요청됨"으로 만든다. 아직 저장 전이라 ID와 생성 시각은 비어 있다.
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
        // 단가 x 개수가 long을 넘으면 조용히 음수가 되지 않게 예외로 막는다
        long itemAmount = Math.multiplyExact(unitPrice, quantity);
        // 배송비 세 칸은 승인 때 정하므로 0으로 시작하고, 진행 중 칸은 이 주문 상품으로 채워 UNIQUE가 중복 요청을 막게 한다
        return new Refund(null, orderId, orderItemId, quantity, buyerId, sellerId, productOptionId, productName, optionName,
                unitPrice, itemAmount, 0L, 0L, 0L, cancelReason, RefundStatus.REQUESTED, null, null, null, null,
                orderItemId, null);
    }

    /**
     * 저장된 환불을 그대로 되살린다. 저장소 어댑터가 DB에서 읽은 값이나 막 저장한 값으로 도메인을 만들 때 쓴다.
     * 요청 때의 입력 검사(개수 1 이상, 단가 0 이상 등)는 다시 하지 않는다. 이미 저장된 행은 그 검사를 거친 값이고,
     * 규칙이 나중에 바뀌어도 예전에 받은 환불을 읽지 못하게 되면 안 되기 때문이다.
     * ID와 상태만 확인한다. 둘 다 DB에서 NOT NULL이라 비었으면 저장 행이 아니라 부르는 쪽 버그다.
     *
     * @throws IllegalArgumentException ID나 상태가 비었으면 (부르는 쪽 버그)
     */
    public static Refund restore(
            Long id,
            Long orderId,
            Long orderItemId,
            int quantity,
            Long buyerId,
            Long sellerId,
            Long productOptionId,
            String productName,
            String optionName,
            long unitPrice,
            long itemAmount,
            long shippingFeeAmount,
            long remoteAreaFeeAmount,
            long deductedShippingFeeAmount,
            String cancelReason,
            RefundStatus status,
            String rejectReason,
            LocalDateTime decidedAt,
            RefundFailedStep failedStep,
            String failureCode,
            Long activeOrderItemId,
            LocalDateTime createdAt
    ) {
        if (id == null || status == null) {
            throw new IllegalArgumentException("저장된 환불을 되살리려면 ID와 상태가 필요합니다.");
        }
        return new Refund(id, orderId, orderItemId, quantity, buyerId, sellerId, productOptionId, productName, optionName,
                unitPrice, itemAmount, shippingFeeAmount, remoteAreaFeeAmount, deductedShippingFeeAmount, cancelReason,
                status, rejectReason, decidedAt, failedStep, failureCode, activeOrderItemId, createdAt);
    }

    /**
     * 판매자 승인으로 처리를 시작하고 배송비 판단을 저장한다. {@code REQUESTED → PROCESSING}
     * 판단은 같은 묶음의 처리 중 요청을 본 결과라 승인 시점에만 정할 수 있다. 한 번 정하면 다시 계산하지 않는다.
     * 아직 끝난 게 아니라 진행 중 칸은 그대로 둔다. 상품 금액은 요청 때 저장한 값을 그대로 쓴다.
     *
     * @param fee 배송비 판단 결과. 받을 수 없는 판단이면 승인하지 말고 자동 거절해야 한다
     * @throws RestApiException         요청됨이 아니면 {@code INVALID_REFUND_STATE}
     * @throws IllegalArgumentException 받을 수 없는 판단을 넘기면 (부르는 쪽 버그)
     */
    public void approve(LocalDateTime now, RefundShippingFee fee) {
        // 판단 검사를 상태 검사보다 먼저 한다. 받을 수 없는 판단이 여기 오는 것은 상태와 상관없이 부르는 쪽 버그라 500으로 먼저 드러낸다.
        // API 경로에서는 서비스가 상태를 먼저 확인하고(409), 받을 수 없으면 승인 대신 자동 거절하므로 이 순서가 응답에 드러나지 않는다
        if (fee == null || !fee.allowed()) {
            throw new IllegalArgumentException("받을 수 없는 배송비 판단으로는 승인할 수 없습니다.");
        }
        requireStatus(RefundStatus.REQUESTED);
        this.decidedAt = now;
        this.status = RefundStatus.PROCESSING;
        this.shippingFeeAmount = fee.shippingFeeAmount();
        this.remoteAreaFeeAmount = fee.remoteAreaFeeAmount();
        this.deductedShippingFeeAmount = fee.deductedShippingFeeAmount();
    }

    /**
     * 돌려줄 금액을 계산한다. 상품 금액 + 돌려줄 배송비 + 도서산간 운임 − 뺀 배송비 (원)
     * 칸마다 따로 저장하고 합계는 저장하지 않는다. 합계까지 저장하면 어긋날 수 있는 칸이 하나 늘어난다.
     */
    public long getRefundAmount() {
        long added = Math.addExact(Math.addExact(itemAmount, shippingFeeAmount), remoteAreaFeeAmount);
        return Math.subtractExact(added, deductedShippingFeeAmount);
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

    /**
     * 처리 중에 다른 모듈 호출이 실패해 멈춘 단계와 이유를 남긴다. 상태는 처리 중 그대로다.
     * 같은 환불이 다시 실패하면 마지막 실패로 덮어쓴다. 다시 처리하는 방법은 후속이라 지우는 메서드는 아직 두지 않는다.
     *
     * @param failureCode 에러 코드 이름 또는 예외 클래스 이름
     * @throws RestApiException         처리 중이 아니면 {@code INVALID_REFUND_STATE}
     * @throws IllegalArgumentException 단계나 이유가 비었으면 (부르는 쪽 버그)
     */
    public void recordFailure(RefundFailedStep step, String failureCode) {
        if (step == null || failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("실패 단계와 이유는 필수입니다.");
        }
        requireStatus(RefundStatus.PROCESSING);
        this.failedStep = step;
        this.failureCode = failureCode;
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
