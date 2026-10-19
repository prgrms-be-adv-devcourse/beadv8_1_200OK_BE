package com.ok.wallet.service.dto;

import java.time.LocalDate;

// 이용 내역 조회 조건. 유형은 컨트롤러가 도메인 enum을 모르도록 문자열로 받고 서비스에서 바꾼다.
public record LedgerHistoryQuery(Long memberId, LocalDate from, LocalDate to, String type, int page, int size) {
}
