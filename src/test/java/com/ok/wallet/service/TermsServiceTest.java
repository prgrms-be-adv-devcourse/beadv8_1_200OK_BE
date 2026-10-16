package com.ok.wallet.service;

import com.ok.common.exception.RestApiException;
import com.ok.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.domain.Terms;
import com.ok.wallet.domain.TermsAgreement;
import com.ok.wallet.domain.TermsType;
import com.ok.wallet.exception.WalletErrorCode;
import com.ok.wallet.repository.TermsAgreementRepository;
import com.ok.wallet.repository.TermsRepository;
import com.ok.wallet.service.dto.TermsAgreeCommand;
import com.ok.wallet.service.dto.TermsResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 마이그레이션으로 들어간 약관 3종 v1을 기준으로 확인한다.
// 동의 시각을 정확한 값으로 확인하려고 Clock을 고정한다 (@DataJpaTest는 ClockConfig를 띄우지 않으므로 여기서 등록한 Clock이 주입된다).
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class, TermsService.class, TermsServiceTest.FixedClockConfig.class})
class TermsServiceTest {
    private static final Long MEMBER_ID = 1L;
    // 2026-10-16 12:00 (Asia/Seoul)
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            ZoneId zone = ZoneId.of("Asia/Seoul");
            return Clock.fixed(NOW.atZone(zone).toInstant(), zone);
        }
    }

    @Autowired
    TermsService termsService;

    @Autowired
    TermsRepository termsRepository;

    @Autowired
    TermsAgreementRepository termsAgreementRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // 조회

    @Test
    void 동의하지_않은_회원은_현재_버전_약관이_모두_미동의로_조회된다() {
        // When
        List<TermsResult> results = termsService.getTerms(MEMBER_ID);

        // Then
        assertThat(results).hasSize(TermsType.values().length);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.version()).isEqualTo(1);
            assertThat(result.required()).isTrue();
            assertThat(result.agreed()).isFalse();
        });
    }

    // 동의

    @Test
    void 필수_약관에_모두_동의하면_약관_버전과_동의_시각이_저장된다() {
        // When
        termsService.agree(MEMBER_ID, currentVersionCommands());

        // Then
        List<TermsAgreement> agreements = agreementsOf(MEMBER_ID);
        assertThat(agreements).hasSize(TermsType.values().length);
        assertThat(agreements).allSatisfy(agreement -> assertThat(agreement.getAgreedAt()).isEqualTo(NOW));
        assertThat(termsService.getTerms(MEMBER_ID)).allSatisfy(result -> assertThat(result.agreed()).isTrue());
    }

    @Test
    void 필수_약관이_하나라도_빠지면_저장되지_않는다() {
        // Given: 개인정보 수집 동의가 빠진 요청
        List<TermsAgreeCommand> commands = List.of(
                new TermsAgreeCommand(TermsType.ELECTRONIC_FINANCIAL_TRANSACTION, 1),
                new TermsAgreeCommand(TermsType.PREPAID_PAYMENT_INSTRUMENT, 1));

        // When & Then
        assertThatThrownBy(() -> termsService.agree(MEMBER_ID, commands))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.REQUIRED_TERMS_NOT_AGREED));
        assertThat(countAgreements(MEMBER_ID)).isZero();
    }

    @Test
    void 요청한_버전이_현재_버전이_아니면_저장되지_않는다() {
        // Given: 사용자가 v1 화면을 보는 사이 전자금융거래 약관이 v2로 개정됨
        insertTerms(TermsType.ELECTRONIC_FINANCIAL_TRANSACTION, 2);

        // When & Then: v1으로 동의 요청
        assertThatThrownBy(() -> termsService.agree(MEMBER_ID, currentVersionCommandsOf(1)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.TERMS_VERSION_OUTDATED));
        assertThat(countAgreements(MEMBER_ID)).isZero();
    }

    @Test
    void 이미_동의한_회원이_다시_요청해도_오류_없이_성공하고_중복_저장되지_않는다() {
        // Given
        termsService.agree(MEMBER_ID, currentVersionCommands());

        // When & Then
        assertThatCode(() -> termsService.agree(MEMBER_ID, currentVersionCommands()))
                .doesNotThrowAnyException();
        assertThat(countAgreements(MEMBER_ID)).isEqualTo(TermsType.values().length);
    }

    // 충전 전 확인

    @Test
    void 필수_약관에_모두_동의했으면_충전_전_확인을_통과한다() {
        // Given
        termsService.agree(MEMBER_ID, currentVersionCommands());

        // When & Then
        assertThatCode(() -> termsService.validateRequiredAgreed(MEMBER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void 약관에_동의하지_않았으면_충전_전_확인에서_막힌다() {
        assertThatThrownBy(() -> termsService.validateRequiredAgreed(MEMBER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.TERMS_AGREEMENT_REQUIRED));
    }

    @Test
    void 약관이_개정되면_이전_버전_동의로는_충전_전_확인에서_막힌다() {
        // Given: v1에 모두 동의한 뒤 전자금융거래 약관이 v2로 개정됨
        termsService.agree(MEMBER_ID, currentVersionCommands());
        insertTerms(TermsType.ELECTRONIC_FINANCIAL_TRANSACTION, 2);

        // When & Then
        assertThatThrownBy(() -> termsService.validateRequiredAgreed(MEMBER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.TERMS_AGREEMENT_REQUIRED));
    }

    // 모든 약관 유형을 현재 버전으로 동의하는 요청
    private List<TermsAgreeCommand> currentVersionCommands() {
        return Arrays.stream(TermsType.values())
                .map(type -> new TermsAgreeCommand(type, currentVersion(type)))
                .toList();
    }

    // 모든 약관 유형을 지정한 버전으로 동의하는 요청
    private List<TermsAgreeCommand> currentVersionCommandsOf(int version) {
        return Arrays.stream(TermsType.values())
                .map(type -> new TermsAgreeCommand(type, version))
                .toList();
    }

    private int currentVersion(TermsType type) {
        return termsRepository.findFirstByTypeOrderByVersionDesc(type).orElseThrow().getVersion();
    }

    private List<TermsAgreement> agreementsOf(Long memberId) {
        List<Long> termsIds = Arrays.stream(TermsType.values())
                .map(type -> termsRepository.findFirstByTypeOrderByVersionDesc(type).orElseThrow())
                .map(Terms::getId)
                .toList();
        return termsAgreementRepository.findAllByMemberIdAndTermsIdIn(memberId, termsIds);
    }

    private long countAgreements(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_terms_agreement WHERE member_id = ?", Long.class, memberId);
    }

    private void insertTerms(TermsType type, int version) {
        jdbcTemplate.update("""
                INSERT INTO wallet_terms (type, version, title, content, created_at, updated_at)
                VALUES (?, ?, '개정 약관', '개정 본문', NOW(6), NOW(6))
                """, type.name(), version);
    }
}
