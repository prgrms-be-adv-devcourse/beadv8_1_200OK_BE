package com.ok.wallet.repository;

import com.ok.wallet.domain.TermsAgreement;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;

// 동의 기록은 추가 전용이라 수정·삭제 메서드를 두지 않는다.
public interface TermsAgreementRepository extends Repository<TermsAgreement, Long> {
    TermsAgreement save(TermsAgreement agreement);

    // 회원이 주어진 약관(버전)들 중 어디에 동의했는지 조회한다.
    List<TermsAgreement> findAllByMemberIdAndTermsIdIn(Long memberId, Collection<Long> termsIds);
}
