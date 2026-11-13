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
 * 로그인 세션 하나의 Refresh Token 저장 기록.
 *
 * <p><b>로그인 한 번 = 행 하나.</b> 로그인하면 행을 새로 만들고, 재발급하면 같은 행의 토큰 해시를 새 값으로 바꾼다(회전).
 * 쓴 토큰마다 행을 남기고 새 행을 만들면 재발급할 때마다 행이 늘어나지만, 같은 행을 바꾸면 한 회원의 행 수가 재발급 횟수가 아니라
 * 유효기간 안에 한 로그인 수만큼으로 묶이고, 만료된 행은 정리 스케줄러가 지운다.
 * 행 하나가 로그인 세션 하나로 이어지므로, 재사용 탐지와 세션 폐기도 이 행을 단위로 붙인다(재사용 탐지 이슈에서 설계).
 *
 * <p><b>덮어쓰는 단위는 회원이 아니라 세션(행)이다.</b>
 * <ul>
 *   <li>다른 기기에서 다시 로그인해도 기존 기기의 세션은 그대로 유효하다. 로그인은 늘 새 행을 만들고 기존 행을 건드리지 않는다.
 *       회원 단위로 덮어쓰면 휴대폰에서 로그인하는 순간 PC의 토큰이 무효가 되므로, 여러 기기 동시 로그인을 막지 않으려고 이렇게 정했다.</li>
 *   <li>같은 회원이 동시에 두 번 로그인하면 둘 다 성공하고 행이 둘 생긴다. 무작위 해시끼리는 유니크 키가 겹치지 않아 서로 기다리지 않는다.</li>
 *   <li>같은 토큰으로 동시에 재발급하면 행을 기본 키로 먼저 잠근 쪽만 회전에 성공하고, 뒤의 요청은 바뀐 해시를 읽어 실패한다(한 번만 사용).</li>
 *   <li>같은 회원의 서로 다른 세션은 다른 행이라, 동시에 재발급해도 서로 기다리지 않는다.</li>
 * </ul>
 * 회원(또는 회원+기기) 유니크 키에 {@code INSERT ... ON DUPLICATE KEY UPDATE}로 덮어쓰는 방식은 쓰지 않는다.
 * 유니크 키 중복을 검사할 때 잡는 다음 키 잠금(next-key lock) 때문에 같은 키로 동시에 로그인하면 교착 상태가 날 수 있고,
 * 기기 단위로 나누려면 기기 식별자를 클라이언트에게서 받아야 하는데 지금 API에는 그 값이 없다.
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
 * 재발급 없이 버려진 세션의 행은 만료 뒤 정리 스케줄러가 지운다.
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

    /** 지금 쓸 수 있는 토큰 원문의 SHA-256 해시 (16진수 소문자 64자) */
    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** 마지막으로 재발급(회전)한 일시. 한 번도 재발급하지 않았으면 null */
    private LocalDateTime rotatedAt;

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

    /**
     * 지금 토큰을 새 토큰으로 바꾼다(회전). 쓴 토큰의 해시는 남기지 않아, 쓴 토큰은 처음부터 없던 토큰과 같이 거절된다.
     * 만료 일시도 새로 받아, 재발급할 때마다 유효기간이 처음부터 다시 시작된다(로그인 때 발급과 같은 수명).
     *
     * @param newTokenHash 새 토큰 원문의 해시 (원문을 넘기지 않도록 주의)
     * @param rotatedAt    재발급 일시
     * @param newExpiresAt 새 토큰의 만료 일시
     */
    public void rotate(String newTokenHash, LocalDateTime rotatedAt, LocalDateTime newExpiresAt) {
        Objects.requireNonNull(newTokenHash, "newTokenHash는 null일 수 없습니다.");
        Objects.requireNonNull(rotatedAt, "rotatedAt은 null일 수 없습니다.");
        Objects.requireNonNull(newExpiresAt, "newExpiresAt은 null일 수 없습니다.");
        this.tokenHash = newTokenHash;
        this.rotatedAt = rotatedAt;
        this.expiresAt = newExpiresAt;
    }

}
