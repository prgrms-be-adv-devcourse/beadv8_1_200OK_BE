package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.TermsAgreementRepository;
import com.ok.wallet.domain.TermsAgreement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 출력 포트 {@link TermsAgreementRepository}를 {@link TermsAgreementJpaRepository}에 넘겨 구현한다.
 */
@Component
@RequiredArgsConstructor
public class TermsAgreementRepositoryAdapter implements TermsAgreementRepository {

    private final TermsAgreementJpaRepository termsAgreementJpaRepository;

    @Override
    public List<TermsAgreement> findAllByMemberIdAndTermsIdIn(Long memberId, Collection<Long> termsIds) {
        return termsAgreementJpaRepository.findAllByMemberIdAndTermsIdIn(memberId, termsIds);
    }

    @Override
    public void insertIfAbsent(Long memberId, Long termsId, LocalDateTime agreedAt) {
        termsAgreementJpaRepository.insertIfAbsent(memberId, termsId, agreedAt);
    }
}
