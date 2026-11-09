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

// 정산 1건을 Kafka로 보내기 위한 발송 대기 기록 (outbox)
// 메시지 본문은 저장하지 않음: payout은 바뀌지 않으므로 보낼 때 payout에서 읽음
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
