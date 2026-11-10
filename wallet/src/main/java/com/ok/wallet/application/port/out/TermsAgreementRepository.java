package com.ok.wallet.application.port.out;

import com.ok.wallet.domain.TermsAgreement;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 약관 동의 기록 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface TermsAgreementRepository {
    // 회원이 주어진 약관(버전)들 중 어디에 동의했는지 조회한다.
    List<TermsAgreement> findAllByMemberIdAndTermsIdIn(Long memberId, Collection<Long> termsIds);

    // 동의 기록이 없으면 저장하고, 이미 있으면 아무것도 하지 않는다.
    // 같은 회원의 동의 요청이 동시에 와도(더블 클릭, 재시도) 유니크 위반 없이 한 번만 저장된다.
    void insertIfAbsent(Long memberId, Long termsId, LocalDateTime agreedAt);
}
