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
 *       멈춘 단계는 이유도 그대로 둔다(결과 불일치 표시를 지키려고, 결정 9).</li>
 *   <li>nextCheckAt 의 뜻은 상태마다 다르다(결정 7).
 *       IN_PROGRESS는 시도 중 만료 시각(지나면 다시 집음, #576), READY는 다음 시도 시각,
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

    /** 시도 중 만료가 반복돼 횟수를 다 쓰고 멈출 때 남기는 이유 */
    public static final String IN_PROGRESS_EXPIRED = "IN_PROGRESS_EXPIRED";

    // TODO(#577): 설정 파일로 뺄지 정한다. 지금은 알람 간격(1분)과 맞춘 임시 값이다
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    // 시도 중인 채로 이 시간이 지나면 멈춘 시도(시도하던 서버가 죽었거나 실패 기록마저 실패함)로 보고 다시 집는다(#576).
    // 지갑 발행이 최대로 기다리는 시간(주소 찾기 max.block.ms 60초 + 저장 확인 10초, 약 70초)보다 길어야 한다.
    // 짧으면 아직 보내는 중인 줄을 다른 서버가 집어 같은 요청을 한 번 더 보낸다(지갑이 refundId로 걸러 돈은 한 번이지만 그 약속에만 기대게 된다)
    private static final Duration IN_PROGRESS_EXPIRY = Duration.ofMinutes(2);
    // 한 단계를 자동으로 시도하는 최대 횟수. 다 쓰면 멈춤으로 남겨 사람이 본다(#576).
    // TODO(#577): 설정 파일로 뺄지 정한다. 다시 시도 간격(1분)과 알람이 끝난 뒤 쉬는 시간(1분)이 겹쳐 시도 사이가 1~2분이라,
    // 처음 시도부터 약 4~8분 동안 다시 시도한다
    static final int MAX_ATTEMPTS = 5;
    // 지갑 결과를 기다리는 시간. 지나도 결과가 없으면 같은 refundId로 다시 요청한다(#577).
    // 정상 답이 늦게 오는 최대 시간(발행 약 70초 + 환불 받기 다시 시도 약 31초 + 지갑 받기 다시 시도 약 7초 + 지갑 결과 보내기 최대 약 40초, 약 148초)보다 길어야 한다.
    // 짧으면 정상 답을 기다리는 중에 다시 보낸다(지갑이 refundId로 걸러 돈은 한 번이지만 그 약속에만 기대게 된다)
    // TODO(#577): 설정 파일로 뺄지 정한다
    private static final Duration RESULT_WAIT = Duration.ofMinutes(5);

    private final Long id;
    private final Long refundId;
    private final RefundStepType type;
    private RefundStepStatus status;
    private int attemptCount;
    private LocalDateTime nextCheckAt;
    private String lastReason;
    // 결과를 처음 기다리기 시작한 시각. 다시 보내도(resendAfterResultTimeout) 바꾸지 않는다(#577).
    // nextCheckAt은 다시 보낼 때마다 밀려 처음부터 기다린 시간을 알 수 없어서 따로 둔다. 결과 대기가 된 적 없으면 null
    private LocalDateTime awaitingSince;
    private final LocalDateTime createdAt;

    private RefundStep(Long id, Long refundId, RefundStepType type, RefundStepStatus status,
                       int attemptCount, LocalDateTime nextCheckAt, String lastReason,
                       LocalDateTime awaitingSince, LocalDateTime createdAt) {
        this.id = id;
        this.refundId = refundId;
        this.type = type;
        this.status = status;
        this.attemptCount = attemptCount;
        this.nextCheckAt = nextCheckAt;
        this.lastReason = lastReason;
        this.awaitingSince = awaitingSince;
        this.createdAt = createdAt;
    }

    /** 단계 차례가 되어 첫 시도를 시작하며 만든다. */
    public static RefundStep start(Long refundId, RefundStepType type, LocalDateTime now) {
        return new RefundStep(null, refundId, type, RefundStepStatus.IN_PROGRESS, 1,
                now.plus(IN_PROGRESS_EXPIRY), null, null, now);
    }

    public static RefundStep restore(Long id, Long refundId, RefundStepType type,
                                     RefundStepStatus status, int attemptCount,
                                     LocalDateTime nextCheckAt, String lastReason,
                                     LocalDateTime createdAt) {
        return restore(id, refundId, type, status, attemptCount, nextCheckAt, lastReason, null, createdAt);
    }

    /** 결과를 처음 기다리기 시작한 시각까지 되살린다. 영속 어댑터가 쓴다. */
    public static RefundStep restore(Long id, Long refundId, RefundStepType type,
                                     RefundStepStatus status, int attemptCount,
                                     LocalDateTime nextCheckAt, String lastReason,
                                     LocalDateTime awaitingSince, LocalDateTime createdAt) {
        return new RefundStep(id, refundId, type, status, attemptCount, nextCheckAt,
                lastReason, awaitingSince, createdAt);
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
        // 처음 결과 대기가 될 때만 채운다. 다시 보낸 뒤 다시 결과 대기가 돼도 처음 시각을 유지한다
        if (awaitingSince == null) {
            awaitingSince = now;
        }
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

    /**
     * 실패해서 다음 시도를 기다린다. 시도 횟수를 다 썼으면 기다리지 않고 멈춘다(이유는 이번 실패 그대로, #576).
     * 단, 이미 한 번 보내고 결과를 기다린 적 있는 줄(결과 대기 시간 초과로 다시 보내다 실패함)은 횟수와 관계없이 멈추지 않고
     * 결과 대기로 되돌린다(#577). 멈춘 줄은 뒤늦게 온 진짜 지갑 답(잔액 부족 등)을 기록하지 못해, 되돌리기(#546)가
     * "지갑이 거절함"과 "돈이 나갔는지 모름"을 가를 수 없기 때문이다. 지갑은 처음 요청을 이미 받았을 수 있고, 같은 refundId는 한 번만 처리한다.
     * 오래 걸리는 환불은 멈춘 환불 찾기(30분)가 사람에게 알린다.
     * 멈췄는지는 바뀐 뒤 {@link #getStatus()}로 본다.
     */
    public boolean failForRetry(String reason, LocalDateTime now) {
        if (status != RefundStepStatus.IN_PROGRESS && status != RefundStepStatus.READY) {
            return false;
        }
        lastReason = normalizeReason(reason);
        if (awaitingSince != null) {
            status = RefundStepStatus.AWAITING_RESULT;
            nextCheckAt = now.plus(RESULT_WAIT);
            return true;
        }
        if (attemptCount >= MAX_ATTEMPTS) {
            status = RefundStepStatus.STOPPED;
            nextCheckAt = null;
            return true;
        }
        status = RefundStepStatus.READY;
        nextCheckAt = now.plus(RETRY_DELAY);
        return true;
    }

    /** 결과 대기인데 기다리는 시각이 지났는지(지갑 결과가 오지 않은 줄, #577) 확인한다. */
    public boolean isResultOverdue(LocalDateTime now) {
        return status == RefundStepStatus.AWAITING_RESULT && nextCheckAt != null && !nextCheckAt.isAfter(now);
    }

    /**
     * 결과를 기다리는 시각이 지난 줄을 같은 요청으로 다시 보내려고 시도 중으로 바꾼다(#577). 만료 시각은 새로 잡는다.
     * 시도 횟수는 올리지 않고, 몇 번이 지나도 멈추지 않는다. 멈추면 끝 상태라 뒤늦게 온 진짜 지갑 답(잔액 부족 등)이 기록되지 않아
     * 되돌리기(#546)가 "지갑이 거절함"과 "돈이 나갔는지 모름"을 가를 수 없다. 횟수는 발행 실패·만료 다시 집기와 함께 써서,
     * 넣으면 Kafka가 잠깐 끊겼던 환불이 다시 요청도 못 하고 멈춘다. 지갑이 같은 refundId를 한 번만 처리하므로 다시 보내도 돈은 한 번만 나간다.
     * 오래 걸리는 환불은 멈춘 환불 찾기(#577)가 사람에게 알린다. 시각이 지나지 않았으면 바꾸지 않는다.
     */
    public boolean resendAfterResultTimeout(LocalDateTime now) {
        if (!isResultOverdue(now)) {
            return false;
        }
        status = RefundStepStatus.IN_PROGRESS;
        nextCheckAt = now.plus(IN_PROGRESS_EXPIRY);
        return true;
    }

    /** 시도 중인데 만료 시각이 지났는지(시도하던 쪽이 끝내지 못하고 멈춘 줄) 확인한다. */
    public boolean isExpired(LocalDateTime now) {
        return status == RefundStepStatus.IN_PROGRESS && nextCheckAt != null && !nextCheckAt.isAfter(now);
    }

    /**
     * 만료된 시도 중 줄을 다시 집는다(#576). 시도 횟수를 올리고 만료 시각을 새로 잡는다.
     * 횟수를 다 썼으면 다시 집지 않고 멈춘다(이유 {@link #IN_PROGRESS_EXPIRED}). 만료되지 않았으면 다른 쪽이 하는 중이라 바꾸지 않는다.
     * 단, 이미 한 번 보내고 결과를 기다린 적 있는 줄(다시 보내는 중 서버가 죽었거나 "결과 대기" 기록이 실패함)은 횟수를 올리지 않고
     * 멈추지도 않는다(#577). 멈추면 늦게 온 진짜 지갑 답을 기록하지 못하는 이유가 {@link #failForRetry}와 같다.
     * 다시 집었는지 멈췄는지는 바뀐 뒤 {@link #getStatus()}로 본다.
     */
    public boolean restartExpired(LocalDateTime now) {
        if (!isExpired(now)) {
            return false;
        }
        if (awaitingSince != null) {
            nextCheckAt = now.plus(IN_PROGRESS_EXPIRY);
            return true;
        }
        if (attemptCount >= MAX_ATTEMPTS) {
            lastReason = IN_PROGRESS_EXPIRED;
            status = RefundStepStatus.STOPPED;
            nextCheckAt = null;
            return true;
        }
        attemptCount++;
        nextCheckAt = now.plus(IN_PROGRESS_EXPIRY);
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
