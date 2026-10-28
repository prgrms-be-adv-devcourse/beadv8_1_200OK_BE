package com.ok.wallet.domain;

import com.ok.wallet.WalletSchema;
import com.ok.common.jpa.entity.BaseTime;
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
import org.hibernate.annotations.Immutable;

/**
 * 지갑 충전 약관의 한 버전. 개정되면 기존 행은 두고 새 버전을 추가한다.
 * 같은 유형 중 가장 높은 버전이 현재 버전이다.
 */
@Entity
@Immutable // 버전별 기록이라 UPDATE가 발생하지 않음
@Table(name = "wallet_terms", catalog = WalletSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Terms extends BaseTime {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private TermsType type;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;
}
