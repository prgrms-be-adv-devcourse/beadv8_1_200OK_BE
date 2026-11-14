package com.ok.member.member.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.member.MemberSchema;
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
 * 다른 서비스로 보낼 회원 메시지 1건의 발송 대기 기록(outbox)을 나타낸다 (#560).
 * 회원 변경과 같은 트랜잭션에서 저장해, 커밋되면 반드시 보내고 롤백되면 보내지 않는다.
 * 회원가입처럼 회원 ID만 보내는 메시지는 본문을 저장하지 않고 보낼 때 만든다. 출고지 우편번호처럼 바뀌는 값을 보내는 메시지는
 * 기록한 시점의 값이 나가야 해서 본문(JSON)을 저장한다(#587).
 * 판매자 하위 도메인의 메시지도 이 표를 쓴다. 판매자 ID가 회원 ID와 같은 값이라 {@code memberId}에 담는다.
 * 보낸 줄은 지우지 않는다(무엇을 언제 보냈는지 남김, 정산 outbox와 같음).
 */
@Entity
@Table(name = "member_outbox", catalog = MemberSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberOutbox extends BaseTime {

    // 마지막 오류 최대 길이 (DB last_error VARCHAR(500)과 맞춤)
    public static final int MAX_ERROR_LENGTH = 500;

    // 본문 최대 길이 (DB payload VARCHAR(1000)과 맞춤)
    public static final int MAX_PAYLOAD_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private MemberOutboxEventType eventType;

    // 연관 엔티티 대신 ID만 둔다. 보낼 때 회원을 읽지 않고, 없는 회원은 외래키가 막는다
    @Column(nullable = false)
    private Long memberId;

    // 보낼 메시지 본문(JSON). 회원 ID만 보내는 메시지는 null. 길이는 DB payload VARCHAR(1000)과 맞춘다
    @Column(length = MAX_PAYLOAD_LENGTH)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberOutboxStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    private LocalDateTime sentAt;

    private MemberOutbox(MemberOutboxEventType eventType, Long memberId, String payload) {
        this.eventType = eventType;
        this.memberId = memberId;
        this.payload = payload;
        this.status = MemberOutboxStatus.PENDING;
    }

    /**
     * 회원가입 메시지를 보낼 대기 줄을 만든다.
     */
    public static MemberOutbox memberRegistered(Long memberId) {
        return new MemberOutbox(MemberOutboxEventType.MEMBER_REGISTERED, memberId, null);
    }

    /**
     * 출고지 우편번호 변경 메시지를 보낼 대기 줄을 만든다.
     * 본문은 시스템이 만든 JSON이라, 비었거나 너무 길면 사용자 입력 오류가 아니라 버그로 본다.
     *
     * @param sellerId 판매자인 회원의 ID
     * @param payload  보낼 메시지 본문(JSON)
     */
    public static MemberOutbox sellerOriginPostalCodeChanged(Long sellerId, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("출고지 우편번호 메시지 본문은 필수입니다.");
        }
        if (payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("outbox 본문은 " + MAX_PAYLOAD_LENGTH + "자 이하여야 합니다.");
        }
        return new MemberOutbox(MemberOutboxEventType.SELLER_ORIGIN_POSTAL_CODE_CHANGED, sellerId, payload);
    }

    public void markSent(LocalDateTime sentAt) {
        this.status = MemberOutboxStatus.SENT;
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
