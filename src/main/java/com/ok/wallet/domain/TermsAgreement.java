package com.ok.wallet.domain;

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
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 회원이 특정 버전의 약관에 동의한 기록. 추가만 하고 수정·삭제하지 않는다.
 * 약관 ID가 유형과 버전을 함께 가리키므로 어떤 버전에 언제 동의했는지 남는다.
 */
@Entity
@Immutable // 동의 기록이라 UPDATE가 발생하지 않음
@Table(name = "wallet_terms_agreement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsAgreement extends BaseTime {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "terms_id", nullable = false)
    private Long termsId;

    @Column(name = "agreed_at", nullable = false)
    private LocalDateTime agreedAt;

    private TermsAgreement(Long memberId, Long termsId, LocalDateTime agreedAt) {
        this.memberId = memberId;
        this.termsId = termsId;
        this.agreedAt = agreedAt;
    }

    // 값은 토큰의 회원 ID와 서버가 조회한 약관에서 오므로, 없으면 코드의 버그로 본다 (값의 출처 기준, #123)
    public static TermsAgreement create(Long memberId, Long termsId, LocalDateTime agreedAt) {
        Objects.requireNonNull(memberId, "memberId는 필수입니다.");
        Objects.requireNonNull(termsId, "termsId는 필수입니다.");
        Objects.requireNonNull(agreedAt, "agreedAt은 필수입니다.");
        return new TermsAgreement(memberId, termsId, agreedAt);
    }
}
