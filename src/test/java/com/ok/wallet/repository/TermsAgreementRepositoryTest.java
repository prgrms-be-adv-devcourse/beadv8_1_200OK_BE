package com.ok.wallet.repository;

import com.ok.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.domain.TermsAgreement;
import com.ok.wallet.domain.TermsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class TermsAgreementRepositoryTest {
    private static final LocalDateTime AGREED_AT = LocalDateTime.of(2026, 10, 16, 12, 0);

    @Autowired
    TermsAgreementRepository termsAgreementRepository;

    @Autowired
    TermsRepository termsRepository;

    // 마이그레이션으로 들어간 약관 v1의 ID
    Long financialTermsId;
    Long prepaidTermsId;

    @BeforeEach
    void setUp() {
        financialTermsId = currentTermsId(TermsType.ELECTRONIC_FINANCIAL_TRANSACTION);
        prepaidTermsId = currentTermsId(TermsType.PREPAID_PAYMENT_INSTRUMENT);
    }

    @Test
    void 동의를_저장하면_동의한_약관과_시각이_기록된다() {
        // When
        TermsAgreement saved = termsAgreementRepository.save(
                TermsAgreement.create(1L, financialTermsId, AGREED_AT));

        // Then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTermsId()).isEqualTo(financialTermsId);
        assertThat(saved.getAgreedAt()).isEqualTo(AGREED_AT);
    }

    @Test
    void 같은_회원이_같은_약관_버전에_두_번_동의하면_저장되지_않는다() {
        // Given
        termsAgreementRepository.save(TermsAgreement.create(1L, financialTermsId, AGREED_AT));

        // When & Then
        assertThatThrownBy(() -> termsAgreementRepository.save(
                TermsAgreement.create(1L, financialTermsId, AGREED_AT.plusMinutes(1))))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_wallet_terms_agreement_member_terms");
    }

    @Test
    void 회원이_주어진_약관들_중_동의한_것만_조회한다() {
        // Given: 회원 1은 전자금융거래 약관만, 회원 2는 선불 약관에 동의
        termsAgreementRepository.save(TermsAgreement.create(1L, financialTermsId, AGREED_AT));
        termsAgreementRepository.save(TermsAgreement.create(2L, prepaidTermsId, AGREED_AT));

        // When
        List<TermsAgreement> agreements = termsAgreementRepository
                .findAllByMemberIdAndTermsIdIn(1L, List.of(financialTermsId, prepaidTermsId));

        // Then: 회원 1의 동의만, 다른 회원의 동의는 섞이지 않음
        assertThat(agreements)
                .extracting(TermsAgreement::getTermsId)
                .containsExactly(financialTermsId);
    }

    private Long currentTermsId(TermsType type) {
        return termsRepository.findFirstByTypeOrderByVersionDesc(type).orElseThrow().getId();
    }
}
