package com.ok.member.member.domain;

import com.ok.member.MemberSchema;
import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static jakarta.persistence.GenerationType.IDENTITY;

@Entity
@Table(name = "member", catalog = MemberSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTime {
    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

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
    @Column(name = "member_type", nullable = false, length = 20)
    private MemberType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    private LocalDateTime withdrawnAt;

    @Column(nullable = false)
    private LocalDateTime termsAgreedAt;

    @Column(nullable = false)
    private LocalDateTime privacyAgreedAt;

    private Member(String loginId, String password, String email, String name,
                   String nickname, MemberType type, LocalDateTime agreedAt) {
        this.loginId = loginId;
        this.password = password;
        this.email = email;
        this.name = name;
        this.nickname = nickname;
        this.type = type;
        this.status = MemberStatus.ACTIVE;
        this.termsAgreedAt = agreedAt;
        this.privacyAgreedAt = agreedAt;
    }

    /**
     * 일반 회원을 생성한다. 판매자가 되려면 가입 후 판매자 신청·승인을 거친다.
     * 이용약관·개인정보 수집·이용 동의는 요청 검증 단계에서 확인된 상태여야 하며, 두 동의 일시는 agreedAt으로 기록한다.
     *
     * @param encodedPassword 인코딩된 비밀번호 (평문 금지)
     * @param agreedAt        약관 동의 일시
     */
    public static Member createUser(String loginId, String encodedPassword, String email,
                                    String name, String nickname, LocalDateTime agreedAt) {
        return new Member(loginId, encodedPassword, email, name, nickname,
                MemberType.USER, agreedAt);
    }

    /**
     * 관리자를 생성한다. 관리자는 운영 전용 계정이라 구매·판매를 하지 않고, 판매자 신청도 할 수 없다.
     * 지금은 앱 시작 시 최초 관리자를 만들 때 쓰고, 관리자 생성 API가 생기면 그 API도 이 메서드를 쓴다.
     *
     * <p>관리자는 서비스 이용자가 아니라 약관 동의 대상이 아니지만, 동의 일시 컬럼이 필수라 계정을 만든 일시를 기록한다.
     */
    public static Member createAdmin(String loginId, String encodedPassword, String email,
                                     String name, String nickname, LocalDateTime createdAt) {
        return new Member(loginId, encodedPassword, email, name, nickname,
                MemberType.ADMIN, createdAt);
    }

    /**
     * 탈퇴한 회원인지 확인한다.
     * 탈퇴 회원을 "없는 회원"으로 볼지 판단하는 기준을 로그인·재발급·내 정보 API가 함께 쓰도록 한곳에 둔다.
     */
    public boolean isWithdrawn() {
        return status == MemberStatus.WITHDRAWN;
    }

    /**
     * 회원이 직접 바꿀 수 있는 이름과 닉네임을 수정한다.
     * 아이디와 이메일은 가입 후 바꿀 수 없다. 닉네임은 다른 회원과 같아도 된다.
     */
    public void updateInfo(String name, String nickname) {
        this.name = name;
        this.nickname = nickname;
    }

}
