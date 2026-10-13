package com.ok.member.auth.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseTime {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    /** 토큰 원문의 SHA-256 해시 (16진수 소문자 64자) */
    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private RefreshToken(Long memberId, String tokenHash, LocalDateTime expiresAt) {
        this.memberId = memberId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /**
     * 저장할 Refresh Token 기록을 만든다.
     *
     * @param memberId  토큰을 발급받은 회원 id
     * @param tokenHash 토큰 원문의 해시 (원문을 넘기지 않도록 주의)
     * @param expiresAt 만료 일시
     */
    public static RefreshToken create(Long memberId, String tokenHash, LocalDateTime expiresAt) {
        Objects.requireNonNull(memberId, "memberId는 null일 수 없습니다.");
        Objects.requireNonNull(tokenHash, "tokenHash는 null일 수 없습니다.");
        Objects.requireNonNull(expiresAt, "expiresAt은 null일 수 없습니다.");
        return new RefreshToken(memberId, tokenHash, expiresAt);
    }

    /**
     * 주어진 시각 기준으로 만료되었는지 확인한다. 만료 시각과 같은 순간부터 만료로 본다.
     * 이후 재발급 이슈에서 토큰 유효성을 판단할 때 사용한다.
     */
    public boolean isExpired(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }

}
