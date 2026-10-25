package com.ok.wallet.repository;

import com.ok.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.domain.Terms;
import com.ok.wallet.domain.TermsType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 약관은 마이그레이션으로만 추가하므로, 새 버전은 SQL로 직접 넣어 확인한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class TermsRepositoryTest {
    @Autowired
    TermsRepository termsRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @EnumSource(TermsType.class)
    void 마이그레이션으로_모든_약관_유형의_첫_버전이_들어_있다(TermsType type) {
        // When
        Terms terms = termsRepository.findFirstByTypeOrderByVersionDesc(type).orElseThrow();

        // Then
        assertThat(terms.getVersion()).isEqualTo(1);
        assertThat(terms.getTitle()).isNotBlank();
        assertThat(terms.getContent()).isNotBlank();
    }

    @Test
    void 새_버전이_추가되면_가장_높은_버전을_현재_버전으로_조회한다() {
        // Given: 전자금융거래 이용약관 개정
        insertTerms(TermsType.ELECTRONIC_FINANCIAL_TRANSACTION, 2);

        // When
        Terms current = termsRepository
                .findFirstByTypeOrderByVersionDesc(TermsType.ELECTRONIC_FINANCIAL_TRANSACTION)
                .orElseThrow();

        // Then
        assertThat(current.getVersion()).isEqualTo(2);
    }

    @Test
    void 같은_유형의_같은_버전은_저장되지_않는다() {
        assertThatThrownBy(() -> insertTerms(TermsType.ELECTRONIC_FINANCIAL_TRANSACTION, 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_wallet_terms_type_version");
    }

    private void insertTerms(TermsType type, int version) {
        jdbcTemplate.update("""
                INSERT INTO wallet_terms (type, version, title, content, created_at, updated_at)
                VALUES (?, ?, '테스트 약관', '테스트 본문', NOW(6), NOW(6))
                """, type.name(), version);
    }
}
