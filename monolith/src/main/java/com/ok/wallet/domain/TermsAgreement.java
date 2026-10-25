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

/**
 * 회원이 특정 버전의 약관에 동의한 기록. 추가만 하고 수정·삭제하지 않는다.
 * 약관 ID가 유형과 버전을 함께 가리키므로 어떤 버전에 언제 동의했는지 남는다.
 * 저장은 동시 요청에서도 중복 없이 처리되도록 TermsAgreementRepository.insertIfAbsent로만 한다.
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
}
