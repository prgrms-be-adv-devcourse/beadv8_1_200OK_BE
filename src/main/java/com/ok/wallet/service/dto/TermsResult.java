package com.ok.wallet.service.dto;

import com.ok.wallet.domain.Terms;

// 현재 버전 약관 한 건과 회원의 동의 여부. 유형은 컨트롤러가 그대로 쓸 수 있게 문자열로 내보낸다 (컨벤션 2.3).
public record TermsResult(
        String type,
        int version,
        String title,
        String content,
        boolean required,
        boolean agreed
) {
    public static TermsResult of(Terms terms, boolean agreed) {
        return new TermsResult(
                terms.getType().name(),
                terms.getVersion(),
                terms.getTitle(),
                terms.getContent(),
                terms.getType().isRequired(),
                agreed);
    }
}
