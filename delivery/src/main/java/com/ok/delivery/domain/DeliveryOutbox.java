package com.ok.delivery.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.delivery.DeliverySchema;
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
import java.util.Objects;

import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * 다른 서비스로 보낼 배송 메시지 1건의 발송 대기 기록(outbox)을 나타낸다 (#644).
 * 배송 생성·출고·배송 완료와 같은 트랜잭션에서 저장해, 커밋되면 반드시 보내고 롤백되면 보내지 않는다.
 * 회원 outbox와 달리 메시지 본문(JSON)을 저장한다. 송장번호·시각 같은 그때의 값을 담아 보낼 때 다시 만들 수 없기 때문이다.
 * 보낸 줄은 지우지 않는다(무엇을 언제 보냈는지 남김, 회원·정산 outbox와 같음).
 */
@Entity
@Table(name = "delivery_outbox", catalog = DeliverySchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryOutbox extends BaseTime {

    // DB last_error VARCHAR(500)과 맞춘다
    public static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private DeliveryOutboxEventType eventType;

    @Column(nullable = false, length = 100)
    private String messageKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryOutboxStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    private LocalDateTime sentAt;

    private DeliveryOutbox(DeliveryOutboxEventType eventType, String messageKey, String payload) {
        this.eventType = Objects.requireNonNull(eventType, "eventType은 필수입니다.");
        this.messageKey = Objects.requireNonNull(messageKey, "messageKey는 필수입니다.");
        this.payload = Objects.requireNonNull(payload, "payload는 필수입니다.");
        this.status = DeliveryOutboxStatus.PENDING;
    }

    /**
     * 보낼 메시지의 대기 줄을 만든다.
     *
     * @param messageKey Kafka 메시지 키. 같은 키의 메시지는 같은 파티션으로 가서 순서가 지켜진다
     * @param payload    보낼 메시지 본문(JSON)
     */
    public static DeliveryOutbox pending(DeliveryOutboxEventType eventType, String messageKey, String payload) {
        return new DeliveryOutbox(eventType, messageKey, payload);
    }

    public void markSent(LocalDateTime sentAt) {
        this.status = DeliveryOutboxStatus.SENT;
        this.sentAt = sentAt;
    }

    /**
     * 발송 실패를 기록한다. 상태는 PENDING 그대로라 다음 실행에서 다시 보낸다.
     * 예외 메시지 길이는 예측할 수 없어, 500자를 넘으면 잘라 저장 실패(같은 줄이 매번 실패)를 막는다.
     */
    public void recordFailure(String error) {
        this.attemptCount++;
        if (error != null && error.length() > MAX_ERROR_LENGTH) {
            this.lastError = error.substring(0, MAX_ERROR_LENGTH);
        } else {
            this.lastError = error;
        }
    }
}
