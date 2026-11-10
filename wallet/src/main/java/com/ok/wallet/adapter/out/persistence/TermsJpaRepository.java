package com.ok.wallet.adapter.out.persistence;

import com.ok.wallet.application.port.out.TermsRepository;
import com.ok.wallet.domain.Terms;
import org.springframework.data.repository.Repository;

// 약관은 마이그레이션으로만 추가하므로 조회 메서드만 둔다.
public interface TermsJpaRepository extends Repository<Terms, Long>, TermsRepository {
}
