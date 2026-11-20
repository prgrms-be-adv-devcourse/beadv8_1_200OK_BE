package com.ok.wallet.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 지갑 충전에 필요한 약관 유형. 제목과 본문은 버전마다 달라질 수 있어 약관 테이블에 둔다.
@Getter
@RequiredArgsConstructor
public enum TermsType {
    ELECTRONIC_FINANCIAL_TRANSACTION(true),   // 전자금융거래 이용약관
    PREPAID_PAYMENT_INSTRUMENT(true),         // 선불전자지급수단 이용약관
    PERSONAL_INFORMATION_COLLECTION(true);    // 개인정보 수집 및 이용 동의

    private final boolean required;
}
