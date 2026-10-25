package com.ok.wallet.domain;

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
@Table(name = "wallet_charge")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Charge extends BaseTime {
    public static final long MIN_AMOUNT = 10_000;
    public static final long AMOUNT_UNIT = 10_000;
    private static final String CHARGE_NO_PREFIX = "CHG_";

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
        if (amount < MIN_AMOUNT || amount % AMOUNT_UNIT != 0) {
            throw new RestApiException(WalletErrorCode.INVALID_CHARGE_AMOUNT);
        }
    }
}
