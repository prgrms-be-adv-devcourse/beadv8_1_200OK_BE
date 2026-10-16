package com.ok.wallet.service.dto;

// 동의 요청의 약관 한 건. 사용자가 화면에서 본 약관의 유형과 버전이다.
// 컨트롤러가 도메인 enum을 쓰지 않도록 유형은 문자열로 받고, 서비스에서 TermsType으로 바꾼다 (컨벤션 2.3).
public record TermsAgreeCommand(String type, int version) {
}
