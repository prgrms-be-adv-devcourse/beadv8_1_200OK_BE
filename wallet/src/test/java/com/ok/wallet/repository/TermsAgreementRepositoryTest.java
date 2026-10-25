package com.ok.wallet.repository;

import com.ok.common.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.domain.TermsAgreement;
import com.ok.wallet.domain.TermsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        termsAgreementRepository.insertIfAbsent(1L, financialTermsId, AGREED_AT);

        // Then
        List<TermsAgreement> agreements = termsAgreementRepository
                .findAllByMemberIdAndTermsIdIn(1L, List.of(financialTermsId));
        assertThat(agreements).singleElement().satisfies(agreement -> {
            assertThat(agreement.getTermsId()).isEqualTo(financialTermsId);
            assertThat(agreement.getAgreedAt()).isEqualTo(AGREED_AT);
        });
    }

    @Test
    void 같은_회원이_같은_약관_버전에_다시_동의하면_처음_기록만_남는다() {
        // Given
        termsAgreementRepository.insertIfAbsent(1L, financialTermsId, AGREED_AT);

        // When: 유니크 제약에 걸려도 예외 없이 넘어간다
        termsAgreementRepository.insertIfAbsent(1L, financialTermsId, AGREED_AT.plusMinutes(1));

        // Then: 처음 동의 시각이 그대로 유지된다
        List<TermsAgreement> agreements = termsAgreementRepository
                .findAllByMemberIdAndTermsIdIn(1L, List.of(financialTermsId));
        assertThat(agreements).singleElement()
                .satisfies(agreement -> assertThat(agreement.getAgreedAt()).isEqualTo(AGREED_AT));
    }

    @Test
    void 회원이_주어진_약관들_중_동의한_것만_조회한다() {
        // Given: 회원 1은 전자금융거래 약관만, 회원 2는 선불 약관에 동의
        termsAgreementRepository.insertIfAbsent(1L, financialTermsId, AGREED_AT);
        termsAgreementRepository.insertIfAbsent(2L, prepaidTermsId, AGREED_AT);

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
