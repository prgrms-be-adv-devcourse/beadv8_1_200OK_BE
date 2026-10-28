package com.ok.wallet.repository;

import com.ok.wallet.domain.Terms;
import com.ok.wallet.domain.TermsType;
import org.springframework.data.repository.Repository;

import java.util.Optional;

// 약관은 마이그레이션으로만 추가하므로 조회 메서드만 둔다.
public interface TermsRepository extends Repository<Terms, Long> {
    // 유형별 가장 높은 버전이 현재 버전이다.
    Optional<Terms> findFirstByTypeOrderByVersionDesc(TermsType type);
}
