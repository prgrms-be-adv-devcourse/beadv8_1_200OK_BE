package com.ok.wallet.domain;

import com.ok.wallet.WalletSchema;
import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.wallet.exception.WalletErrorCode;
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
import java.util.UUID;

/**
 * 예치금 충전 건. 토스 결제창을 호출하기 전에 서버가 먼저 만들어, 승인 시점에 금액을 대조하는 기준이 된다.
 * 처리 중(IN_PROGRESS)으로의 전이는 동시 승인 요청 중 하나만 성공해야 해서 저장소의 조건부 UPDATE로 한다.
 */
@Entity
@Table(name = "wallet_charge", catalog = WalletSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Charge extends BaseTime {
    // 결제 건마다 PG 수수료와 승인 호출이 생기므로 초소액 충전을 막는다. 단위 제한은 두지 않는다 (#274)
    public static final long MIN_AMOUNT = 10_000;
    private static final String CHARGE_NO_PREFIX = "CHG_";
    // 관리자가 토스 결제를 취소해 실패로 끝낸 처리 중 건의 실패 코드
    private static final String PAYMENT_CANCELED_FAILURE_CODE = "PAYMENT_CANCELED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 토스 orderId로 쓰이는 외부 노출용 번호
    @Column(name = "charge_no", nullable = false, unique = true, length = 40)
    private String chargeNo;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChargeStatus status;

    @Column(name = "payment_key", length = 200)
    private String paymentKey;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    // 복구가 다음에 토스에 확인할 시각. 처리 중인 건에서만 보고, 비어 있으면 복구가 고르지 않는다(사람 확인 대기)
    @Column(name = "next_check_at")
    private LocalDateTime nextCheckAt;

    // 복구가 토스에 확인한 횟수. 상한을 넘으면 더 묻지 않고 멈춘다
    @Column(name = "check_count", nullable = false)
    private int checkCount;

    // 실패로 처리된 뒤 토스가 승인한 것을 안 시각. 돈은 빠졌는데 잔액이 늘지 않은 상태라 관리자가 결제를 취소할 대상이다.
    // 로그만으로는 놓칠 수 있고, 실패 건은 DB만 봐서는 승인 여부를 알 수 없어 따로 남긴다
    @Column(name = "approved_after_failure_at")
    private LocalDateTime approvedAfterFailureAt;

    // 관리자가 토스 결제 취소를 요청한 시각. 토스 취소는 트랜잭션 밖이라, 그 사이 같은 건이 잔액에 반영되면
    // 환불과 잔액 증가가 함께 일어난다. 토스를 부르기 전에 남겨 두고, 값이 있는 처리 중 건은 완료·실패로 바꾸지 않는다
    @Column(name = "payment_cancel_requested_at")
    private LocalDateTime paymentCancelRequestedAt;

    // 관리자가 토스 결제를 취소(환불)한 시각. 새 상태를 두면 상태 규칙과 한도 합계를 모두 고쳐야 해서 상태는 실패로 둔다
    @Column(name = "payment_canceled_at")
    private LocalDateTime paymentCanceledAt;

    private Charge(Long memberId, long amount) {
        this.chargeNo = CHARGE_NO_PREFIX + UUID.randomUUID();
        this.memberId = memberId;
        this.amount = amount;
        this.status = ChargeStatus.PENDING;
    }

    /**
     * 충전을 요청한다. 금액은 사용자 입력이라 규칙 위반은 422로 응답한다.
     * 잔액 보유 한도는 지갑 잔액과 처리 중 충전을 함께 봐야 해서 서비스에서 확인한다.
     */
    public static Charge request(Long memberId, long amount) {
        validateAmount(amount);
        return new Charge(memberId, amount);
    }

    public boolean isOwnedBy(Long memberId) {
        return this.memberId.equals(memberId);
    }

    public boolean hasAmount(long amount) {
        return this.amount == amount;
    }

    /** 승인되어 잔액에 반영된 충전 건을 완료한다. 처리 중인 건만 완료할 수 있다. */
    public void complete(String paymentKey, LocalDateTime completedAt) {
        changeStatus(ChargeStatus.COMPLETED);
        this.paymentKey = paymentKey;
        this.completedAt = completedAt;
    }

    /** 실패로 처리된 충전 건을 토스가 승인한 것을 기록한다. 같은 건을 여러 번 알게 돼도 처음 안 시각을 남긴다. */
    public void markApprovedAfterFailure(LocalDateTime knownAt) {
        if (status != ChargeStatus.FAILED) {
            throw new IllegalStateException("실패한 충전 건만 승인 뒤 실패로 기록할 수 있습니다. chargeNo=" + chargeNo + ", status=" + status);
        }
        if (approvedAfterFailureAt == null) {
            approvedAfterFailureAt = knownAt;
        }
    }

    /** 관리자가 토스 결제 취소를 요청한 처리 중 건이다. 취소 결과가 정해질 때까지 완료·실패로 바꾸지 않는다. */
    public boolean isPaymentCancelRequested() {
        return status == ChargeStatus.IN_PROGRESS && paymentCancelRequestedAt != null;
    }

    /**
     * 관리자가 토스 결제 취소를 요청한 것을 남긴다. 처리 중인 건만 남기고, 이미 있으면 처음 요청한 시각을 둔다.
     * 표시는 취소가 기록되면 끝나고 코드로 지우지 않는다. 거절을 받은 요청은 다른 요청의 취소가 토스에서
     * 아직 진행 중인지 알 수 없어서, 지우면 그 사이 잔액이 반영돼 환불과 함께 일어날 수 있다.
     */
    public void requestPaymentCancel(LocalDateTime requestedAt) {
        if (status != ChargeStatus.IN_PROGRESS) {
            throw new IllegalStateException("처리 중인 충전 건만 결제 취소를 요청할 수 있습니다. chargeNo=" + chargeNo + ", status=" + status);
        }
        if (paymentCancelRequestedAt == null) {
            paymentCancelRequestedAt = requestedAt;
        }
    }

    /** 복구가 토스 답만으로 정하지 못해 멈춘 건이다. 관리자가 처리할 때까지 처리 중으로 남는다. */
    public boolean isHeldForReview() {
        return status == ChargeStatus.IN_PROGRESS && nextCheckAt == null;
    }

    /** 실패로 처리된 뒤 토스가 승인해, 관리자가 결제를 취소해야 하는 건이다. */
    public boolean isAwaitingPaymentCancel() {
        return status == ChargeStatus.FAILED && approvedAfterFailureAt != null && paymentCanceledAt == null;
    }

    /**
     * 관리자가 토스 결제를 취소(환불)한 것을 기록한다. 처리 중인 건은 실패로 바꾸고, 이미 실패한 건은 원래 실패 사유를 그대로 둔다.
     * 같은 건을 다시 기록해도 처음 취소 시각을 남긴다.
     */
    public void recordPaymentCanceled(String reason, LocalDateTime canceledAt) {
        if (status == ChargeStatus.IN_PROGRESS) {
            fail(PAYMENT_CANCELED_FAILURE_CODE, reason, canceledAt);
        }
        if (status != ChargeStatus.FAILED) {
            throw new IllegalStateException("처리 중이거나 실패한 충전 건만 결제 취소를 기록할 수 있습니다. chargeNo=" + chargeNo + ", status=" + status);
        }
        if (paymentCanceledAt == null) {
            paymentCanceledAt = canceledAt;
        }
    }

    /** 결제 실패 또는 명확한 승인 실패로 충전 건을 실패 처리한다. 대기·처리 중인 건만 실패할 수 있다. */
    public void fail(String failureCode, String failureMessage, LocalDateTime failedAt) {
        changeStatus(ChargeStatus.FAILED);
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.failedAt = failedAt;
    }

    // 상태 순서는 서버 흐름이 정하므로, 어긋나면 코드의 버그다 (값의 출처 기준, #123)
    private void changeStatus(ChargeStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "충전 건 상태를 바꿀 수 없습니다. chargeNo=" + chargeNo + ", " + status + " -> " + next);
        }
        this.status = next;
    }

    private static void validateAmount(long amount) {
        if (amount < MIN_AMOUNT) {
            throw new RestApiException(WalletErrorCode.INVALID_CHARGE_AMOUNT);
        }
    }
}
