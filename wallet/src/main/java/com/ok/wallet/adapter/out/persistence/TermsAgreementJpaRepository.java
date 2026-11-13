package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.WalletSchema;
import com.ok.wallet.domain.TermsAgreement;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

// 동의 기록은 추가 전용이라 수정·삭제 메서드를 두지 않는다.
public interface TermsAgreementJpaRepository extends Repository<TermsAgreement, Long> {

    List<TermsAgreement> findAllByMemberIdAndTermsIdIn(Long memberId, Collection<Long> termsIds);

    // MySQL 전용 문법이라 네이티브 쿼리로 작성하며, BaseTime을 거치지 않으므로 생성 시각도 동의 시각으로 넣는다.
    // 컬럼을 직접 나열하므로 TermsAgreement·BaseTime에 NOT NULL 컬럼이 추가되면 이 쿼리도 함께 수정해야 한다.
    // 네이티브 쿼리는 엔티티의 catalog 지정이 적용되지 않으므로 테이블 이름을 스키마로 직접 한정한다
    @Modifying
    @Query(value = "INSERT INTO " + WalletSchema.NAME + """
        .wallet_terms_agreement (member_id, terms_id, agreed_at, created_at, updated_at)
        VALUES (:memberId, :termsId, :agreedAt, :agreedAt, :agreedAt)
        ON DUPLICATE KEY UPDATE member_id = member_id
        """, nativeQuery = true)
    void insertIfAbsent(@Param("memberId") Long memberId,
                        @Param("termsId") Long termsId,
                        @Param("agreedAt") LocalDateTime agreedAt);
}
