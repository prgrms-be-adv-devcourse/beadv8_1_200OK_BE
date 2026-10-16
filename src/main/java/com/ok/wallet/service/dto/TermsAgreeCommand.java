package com.ok.wallet.service.dto;

import com.ok.wallet.domain.TermsType;

// 동의 요청의 약관 한 건. 사용자가 화면에서 본 약관의 유형과 버전이다.
public record TermsAgreeCommand(TermsType type, int version) {
}
