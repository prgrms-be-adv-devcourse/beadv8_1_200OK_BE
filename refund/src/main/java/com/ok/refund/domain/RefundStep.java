package com.ok.refund.domain;

import java.time.Duration;
import java.time.LocalDateTime;

import lombok.Getter;

/**
 * 환불 승인 뒤 한 단계(주문·지갑·상품)의 진행 기록.
 *
 * <ul>
 *   <li>환불 하나에 단계마다 한 행이다. 그 단계 차례가 되면 만든다(결정 1).
 *       아직 오지 않은 단계는 행이 없다.</li>
 *   <li>끝 상태(SUCCEEDED, REFUSED, STOPPED)는 다시 바뀌지 않는다(결정 2).
 *       지갑 결과 리스너 스레드에서 성공이 먼저 도착한 뒤, 요청 스레드가 늦게
 *       "결과 대기"를 써도 성공을 덮어쓰지 못하게 하기 위해서다.
 *       단 STOPPED는 이유가 다르면 이유만 바꾼다.</li>
 *   <li>nextCheckAt 의 뜻은 상태마다 다르다(결정 7).
 *       IN_PROGRESS는 시도 중 만료 시각, READY는 다음 시도 시각,
 *       AWAITING_RESULT는 결과를 기다리는 한도 시각이고, 끝 상태는 null이다.</li>
 * </ul>
 *
 * 전이 메서드는 상태를 바꾸면 true, 바꾸지 않으면 false를 돌려준다.
 * 시각은 도메인이 Clock을 갖지 않도록 호출하는 쪽이 now로 넘긴다.
 */
@Getter
public class RefundStep {

    /** 이유 최대 길이. 환불 실패 기록의 기존 규칙과 같다 */
    public static final int MAX_REASON_LENGTH = 100;

    // TODO(#576): 다시 시도 간격 정책을 정한다
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    /** 시도 중인 채로 이 시간이 지나면 멈춘 시도로 본다 */
    private static final Duration IN_PROGRESS_EXPIRY = Duration.ofMinutes(1);
    // TODO(#577): 결과를 기다리는 시간 정책을 정한다
    private static final Duration RESULT_WAIT = Duration.ofMinutes(30);

    private final Long id;
    private final Long refundId;
    private final RefundStepType type;
    private RefundStepStatus status;
    private int attemptCount;
    private LocalDateTime nextCheckAt;
    private String lastReason;
    private final LocalDateTime createdAt;

    private RefundStep(Long id, Long refundId, RefundStepType type, RefundStepStatus status,
                       int attemptCount, LocalDateTime nextCheckAt, String lastReason,
                       LocalDateTime createdAt) {
        this.id = id;
        this.refundId = refundId;
        this.type = type;
        this.status = status;
        this.attemptCount = attemptCount;
        this.nextCheckAt = nextCheckAt;
        this.lastReason = lastReason;
        this.createdAt = createdAt;
    }

    /** 단계 차례가 되어 첫 시도를 시작하며 만든다. */
    public static RefundStep start(Long refundId, RefundStepType type, LocalDateTime now) {
        return new RefundStep(null, refundId, type, RefundStepStatus.IN_PROGRESS, 1,
                now.plus(IN_PROGRESS_EXPIRY), null, now);
    }

    public static RefundStep restore(Long id, Long refundId, RefundStepType type,
                                     RefundStepStatus status, int attemptCount,
                                     LocalDateTime nextCheckAt, String lastReason,
                                     LocalDateTime createdAt) {
        return new RefundStep(id, refundId, type, status, attemptCount, nextCheckAt,
                lastReason, createdAt);
    }

    /** 다시 시도를 시작한다. READY일 때만 되고, 이미 시도 중이면 다른 곳이 하고 있으므로 false. */
    public boolean begin(LocalDateTime now) {
        if (status != RefundStepStatus.READY) {
            return false;
        }
        status = RefundStepStatus.IN_PROGRESS;
        attemptCount++;
        nextCheckAt = now.plus(IN_PROGRESS_EXPIRY);
        return true;
    }

    /** 요청을 보내고 결과를 기다린다. */
    public boolean awaitResult(LocalDateTime now) {
        if (status != RefundStepStatus.IN_PROGRESS && status != RefundStepStatus.READY) {
            return false;
        }
        status = RefundStepStatus.AWAITING_RESULT;
        nextCheckAt = now.plus(RESULT_WAIT);
        return true;
    }

    public boolean succeed() {
        if (status.isFinished()) {
            return false;
        }
        status = RefundStepStatus.SUCCEEDED;
        nextCheckAt = null;
        return true;
    }

    public boolean refuse(String reason) {
        if (status.isFinished()) {
            return false;
        }
        lastReason = normalizeReason(reason);
        status = RefundStepStatus.REFUSED;
        nextCheckAt = null;
        return true;
    }

    /** 실패해서 다음 시도를 기다린다. */
    public boolean failForRetry(String reason, LocalDateTime now) {
        if (status != RefundStepStatus.IN_PROGRESS && status != RefundStepStatus.READY) {
            return false;
        }
        lastReason = normalizeReason(reason);
        status = RefundStepStatus.READY;
        nextCheckAt = now.plus(RETRY_DELAY);
        return true;
    }

    /** 자동 처리를 멈춘다. 이미 끝난 단계(성공·거절됨·멈춤)는 이유도 바꾸지 않는다. */
    public boolean stop(String reason) {
        // 멈춘 줄도 끝 상태라 이유까지 그대로 둔다. 처음 멈춘 이유를 덮으면 결과 불일치(RESULT_MISMATCH) 표시가
        // 뒤에 온 보통 실패로 지워져, 되돌리기(#546)가 돈이 나갔는지 모르는 환불을 보통 지갑 실패로 보고 주문을 되돌릴 수 있다
        String normalized = normalizeReason(reason);
        if (status.isFinished()) {
            return false;
        }
        lastReason = normalized;
        status = RefundStepStatus.STOPPED;
        nextCheckAt = null;
        return true;
    }

    /** 멈춰 있고 같은 이유인지. */
    public boolean isStoppedWith(String reason) {
        return status == RefundStepStatus.STOPPED && normalizeReason(reason).equals(lastReason);
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("이유는 비어 있을 수 없습니다.");
        }
        return reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
    }
}
