package com.ok.wallet.service.dto;

import com.ok.wallet.domain.Terms;
import com.ok.wallet.domain.TermsType;

// 현재 버전 약관 한 건과 회원의 동의 여부
public record TermsResult(
        TermsType type,
        int version,
        String title,
        String content,
        boolean required,
        boolean agreed
) {
    public static TermsResult of(Terms terms, boolean agreed) {
        return new TermsResult(
                terms.getType(),
                terms.getVersion(),
                terms.getTitle(),
                terms.getContent(),
                terms.getType().isRequired(),
                agreed);
    }
}
