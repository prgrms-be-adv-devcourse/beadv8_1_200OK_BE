package com.ok.order.order.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.order.OrderSchema;
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
 * 주문이 Kafka로 보낼 메시지 1건의 발송 대기 기록(outbox)이다 (#666).
 * 주문 변경과 같은 트랜잭션에서 저장해, 커밋되면 반드시 보내고 롤백되면 보내지 않는다.
 * 메시지 종류 대신 토픽 이름을 그대로 담아, 보내는 쪽이 메시지 종류를 몰라도 되게 한다.
 * 보낸 줄은 지우지 않는다(무엇을 언제 보냈는지 남김, 회원·정산 outbox와 같음).
 */
@Entity
@Table(name = "order_outbox", catalog = OrderSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderOutbox extends BaseTime {

    // DB 칸 길이와 맞춘다. 토픽 이름은 Kafka 제한(249자)과 같다
    static final int MAX_TOPIC_LENGTH = 249;
    static final int MAX_KEY_LENGTH = 255;
    static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = MAX_TOPIC_LENGTH)
    private String topic;

    @Column(nullable = false, length = MAX_KEY_LENGTH)
    private String messageKey;

    // 기록한 시점의 값이 그대로 나가야 해서 본문(JSON)을 저장한다
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderOutboxStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    private LocalDateTime sentAt;

    private OrderOutbox(String topic, String messageKey, String payload) {
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.status = OrderOutboxStatus.PENDING;
    }

    /**
     * 보낼 메시지를 대기 줄로 만든다.
     * 값은 주문 코드가 만들어 넘기므로, 비었거나 너무 길면 사용자 입력 오류가 아니라 버그로 본다.
     *
     * @param messageKey Kafka 메시지 키. 같은 키는 같은 파티션으로 가서 순서가 지켜진다
     * @param payload    보낼 메시지 본문(JSON)
     */
    public static OrderOutbox pending(String topic, String messageKey, String payload) {
        requireText(topic, "topic");
        requireText(messageKey, "messageKey");
        requireText(payload, "payload");
        if (topic.length() > MAX_TOPIC_LENGTH) {
            throw new IllegalArgumentException("topic은 " + MAX_TOPIC_LENGTH + "자 이하여야 합니다.");
        }
        if (messageKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("messageKey는 " + MAX_KEY_LENGTH + "자 이하여야 합니다.");
        }
        return new OrderOutbox(topic, messageKey, payload);
    }

    public boolean isPending() {
        return status == OrderOutboxStatus.PENDING;
    }

    // 브로커가 저장을 확인했다. 커밋 직후 발송과 재발송이 같은 줄을 함께 보냈으면 처음 보낸 시각을 남긴다
    public void markSent(LocalDateTime sentAt) {
        if (status == OrderOutboxStatus.SENT) {
            return;
        }
        this.status = OrderOutboxStatus.SENT;
        this.sentAt = sentAt;
    }

    /**
     * 발송 실패를 기록한다. 상태는 PENDING 그대로라 재발송 스케줄러가 다시 보낸다.
     * 원인은 칸 길이에 맞춰 자른다. 길다는 이유로 기록이 실패하면 시도 횟수가 남지 않는다.
     */
    public void recordFailure(String error) {
        this.attemptCount++;
        this.lastError = error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "은(는) 필수입니다.");
        }
    }
}
