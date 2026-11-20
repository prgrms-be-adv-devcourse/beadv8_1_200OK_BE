package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.Terms;
import com.ok.wallet.domain.TermsType;

import java.util.Optional;

/**
 * 약관 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface TermsRepository {
    // 유형별 가장 높은 버전이 현재 버전이다.
    Optional<Terms> findFirstByTypeOrderByVersionDesc(TermsType type);
}
