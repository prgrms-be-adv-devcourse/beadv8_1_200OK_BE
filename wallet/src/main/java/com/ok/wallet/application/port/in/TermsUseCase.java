package com.ok.wallet.application.port.in;

import com.ok.wallet.application.dto.TermsAgreeCommand;
import com.ok.wallet.application.dto.TermsResult;

import java.util.List;

/**
 * 지갑 충전 약관 조회와 동의 유스케이스.
 */
public interface TermsUseCase {

    /** 현재 버전 약관과 회원의 약관별 동의 여부를 반환한다. */
    List<TermsResult> getTerms(Long memberId);

    /**
     * 요청한 약관에 동의한다.
     * 요청한 버전이 모두 현재 버전이고 필수 약관이 모두 포함된 경우에만 저장하며,
     * 이미 동의한 약관은 다시 저장하지 않아 재요청해도 오류 없이 성공한다.
     */
    void agree(Long memberId, List<TermsAgreeCommand> commands);
}
