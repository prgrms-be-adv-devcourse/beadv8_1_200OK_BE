package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.TermsRepository;
import com.ok.wallet.domain.Terms;
import com.ok.wallet.domain.TermsType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 출력 포트 {@link TermsRepository}를 {@link TermsJpaRepository}에 넘겨 구현한다.
 */
@Component
@RequiredArgsConstructor
public class TermsRepositoryAdapter implements TermsRepository {

    private final TermsJpaRepository termsJpaRepository;

    @Override
    public Optional<Terms> findFirstByTypeOrderByVersionDesc(TermsType type) {
        return termsJpaRepository.findFirstByTypeOrderByVersionDesc(type);
    }
}
