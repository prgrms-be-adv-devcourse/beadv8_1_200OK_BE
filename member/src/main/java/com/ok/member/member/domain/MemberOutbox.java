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
 * 메시지 본문은 저장하지 않는다. 지금 보내는 회원가입 메시지는 회원 ID만 담아 보낼 때 만들면 된다.
 * 보낸 줄은 지우지 않는다(무엇을 언제 보냈는지 남김, 정산 outbox와 같음).
 */
@Entity
@Table(name = "member_outbox", catalog = MemberSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberOutbox extends BaseTime {

    // 마지막 오류 최대 길이 (DB last_error VARCHAR(500)과 맞춤)
    public static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private MemberOutboxEventType eventType;

    // 연관 엔티티 대신 ID만 둔다. 보낼 때 회원을 읽지 않고, 없는 회원은 외래키가 막는다
    @Column(nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberOutboxStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    private LocalDateTime sentAt;

    private MemberOutbox(MemberOutboxEventType eventType, Long memberId) {
        this.eventType = eventType;
        this.memberId = memberId;
        this.status = MemberOutboxStatus.PENDING;
    }

    /**
     * 회원가입 메시지를 보낼 대기 줄을 만든다.
     */
    public static MemberOutbox memberRegistered(Long memberId) {
        return new MemberOutbox(MemberOutboxEventType.MEMBER_REGISTERED, memberId);
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
