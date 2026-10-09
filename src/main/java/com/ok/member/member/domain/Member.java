package com.ok.member.member.domain;

import com.ok.common.jpa.entity.BaseIdAndTime;
import com.ok.member.MemberRole;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseIdAndTime {

    @Column(nullable = false, length = 20)
    private String loginId;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    private LocalDateTime withdrawnAt;

    @Column(nullable = false)
    private LocalDateTime termsAgreedAt;

    @Column(nullable = false)
    private LocalDateTime privacyAgreedAt;

    private Member(String loginId, String password, String email, String name,
                   String nickname, MemberRole role, LocalDateTime agreedAt) {
        this.loginId = loginId;
        this.password = password;
        this.email = email;
        this.name = name;
        this.nickname = nickname;
        this.role = role;
        this.status = MemberStatus.ACTIVE;
        this.termsAgreedAt = agreedAt;
        this.privacyAgreedAt = agreedAt;
    }

    /**
     * 일반 회원(구매자)을 생성한다.
     * 이용약관·개인정보 수집·이용 동의는 요청 검증 단계에서 확인된 상태여야 하며, 두 동의 일시는 agreedAt으로 기록한다.
     *
     * @param encodedPassword 인코딩된 비밀번호 (평문 금지)
     * @param agreedAt        약관 동의 일시
     */
    public static Member createBuyer(String loginId, String encodedPassword, String email,
                                     String name, String nickname, LocalDateTime agreedAt) {
        return new Member(loginId, encodedPassword, email, name, nickname,
                MemberRole.BUYER, agreedAt);
    }

}
