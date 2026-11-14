package com.ok.wallet.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.wallet.WalletSchema;
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
 * 지갑이 Kafka로 받은 메시지 중 끝내 처리하지 못한 메시지의 기록이다.
 * 메시지는 토픽·파티션·오프셋으로 하나로 정해지므로 이 셋이 같으면 같은 메시지로 보고 한 번만 남긴다.
 * 실패 시각은 생성 시각(createdAt)으로 본다.
 */
@Entity
@Table(name = "wallet_message_failure", catalog = WalletSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletMessageFailure extends BaseTime {
    static final int MAX_MESSAGE_KEY_LENGTH = 255;
    static final int MAX_FAILURE_REASON_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 249)
    private String topic;

    @Column(nullable = false)
    private int kafkaPartition;

    @Column(nullable = false)
    private long kafkaOffset;

    @Column(length = MAX_MESSAGE_KEY_LENGTH)
    private String messageKey;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String payload;

    @Column(nullable = false, length = MAX_FAILURE_REASON_LENGTH)
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletMessageFailureStatus status;

    private WalletMessageFailure(String topic, int kafkaPartition, long kafkaOffset, String messageKey, String payload,
                                 String failureReason) {
        this.topic = topic;
        this.kafkaPartition = kafkaPartition;
        this.kafkaOffset = kafkaOffset;
        this.messageKey = messageKey;
        this.payload = payload;
        this.failureReason = failureReason;
        this.status = WalletMessageFailureStatus.PENDING;
    }

    /**
     * 처리하지 못한 메시지를 대기 상태로 기록한다.
     * 키와 실패 이유는 칸 길이에 맞춰 자른다. 기록 저장이 실패하면 그 메시지에서 받기가 멈추므로, 값이 길다는 이유로 실패하지 않게 한다.
     */
    public static WalletMessageFailure record(String topic, int kafkaPartition, long kafkaOffset, String messageKey,
                                              String payload, String failureReason) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic은 필수입니다.");
        }
        if (failureReason == null || failureReason.isBlank()) {
            throw new IllegalArgumentException("failureReason은 필수입니다.");
        }
        return new WalletMessageFailure(topic, kafkaPartition, kafkaOffset,
                truncate(messageKey, MAX_MESSAGE_KEY_LENGTH), payload, truncate(failureReason, MAX_FAILURE_REASON_LENGTH));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
