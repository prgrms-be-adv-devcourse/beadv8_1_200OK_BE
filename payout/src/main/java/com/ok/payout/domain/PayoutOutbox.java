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

/**
 * 정산 1건을 Kafka로 보내기 위한 발송 대기 기록(outbox)을 나타낸다.
 * 메시지 본문은 저장하지 않는다. payout은 바뀌지 않으므로 보낼 때 payout에서 읽는다.
 */
@Entity
@Table(name = "payout_outbox", catalog = PayoutSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutOutbox extends BaseTime {

    // 마지막 오류 최대 길이 (DB last_error VARCHAR(500)과 맞춤)
    public static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    // 정산은 ID 숫자만 둠 (PayoutFailure와 같은 이유). 없는 ID와 같은 정산의 중복 줄은 DB가 막음
    @Column(nullable = false)
    private Long payoutId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutOutboxStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    private LocalDateTime sentAt;

    public static PayoutOutbox pending(Long payoutId) {
        PayoutOutbox outbox = new PayoutOutbox();
        outbox.payoutId = payoutId;
        outbox.status = PayoutOutboxStatus.PENDING;
        return outbox;
    }

    public void markSent(LocalDateTime sentAt) {
        this.status = PayoutOutboxStatus.SENT;
        this.sentAt = sentAt;
    }

    // 대사에서 지갑이 "입금 없음"으로 답한 정산을 다시 보낼 것으로 되돌림 (#620). 짧은 간격 재발송이 다시 보냄
    // sentAt은 비움: PENDING인데 보낸 시각이 남아 있으면 "보냈는데 왜 대기인지" 헷갈림
    // attemptCount는 그대로 둠: 실패한 발송 시도의 기록이고, 보내는 순서(덜 시도한 줄부터)에만 쓰여 되돌려도 해가 없음
    public void markPendingAgain() {
        this.status = PayoutOutboxStatus.PENDING;
        this.sentAt = null;
    }

    // 예외 메시지 길이는 예측할 수 없어, 500자를 넘으면 잘라 저장 실패(같은 줄이 매번 실패)를 막음
    public void recordFailure(String error) {
        this.attemptCount++;
        if (error != null && error.length() > MAX_ERROR_LENGTH) {
            this.lastError = error.substring(0, MAX_ERROR_LENGTH);
        } else {
            this.lastError = error;
        }
    }
}
