package com.ok.member.auth.domain;

import com.ok.member.MemberSchema;
import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 발급된 Refresh Token의 저장 기록.
 *
 * <p><b>원문을 저장하지 않는다.</b> 클라이언트에게 준 토큰 원문은 DB에 남기지 않고 SHA-256 해시만 저장한다.
 * DB가 유출되어도 해시로는 원래 토큰을 알아낼 수 없어, 유출된 데이터로 로그인 상태를 탈취할 수 없다.
 *
 * <p>auth는 추후 member에서 별도 모듈로 분리할 예정이라, 다른 모듈의 ID로 보고 member와 외래 키를 두지 않는다.
 * (마이그레이션 컨벤션 8.6) 회원별 조회를 위한 인덱스(idx_refresh_token_member_id)만 둔다.
 * 엔티티도 {@code @ManyToOne Member} 대신 memberId만 가진다. 분리할 때 엔티티를 바꾸지 않아도 되고,
 * 토큰을 다룰 때 회원 정보가 필요 없어 불필요한 조회를 만들지 않는다.
 *
 * <p>외래 키가 없으므로 탈퇴·정지 회원의 토큰 정리는 DB가 아닌 애플리케이션이 맡는다.
 * (로그아웃·회원 탈퇴 이슈에서 member의 이벤트를 구독해 해당 회원의 토큰을 삭제)
 */
@Entity
@Table(name = "refresh_token", catalog = MemberSchema.NAME)
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
     * 재발급할 때 토큰이 아직 쓸 수 있는지 판단한다.
     */
    public boolean isExpired(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }

}
