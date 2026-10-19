package com.ok.wallet.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

// 페이지 규칙은 주문 목록 조회(OrderPageRequest)와 같게 맞춘다. 유형 값 확인은 서비스가 한다.
@Schema(description = "이용 내역 조회 조건")
public record LedgerHistoryRequest(
        @Schema(description = "조회 시작일 (포함)", example = "2026-10-01")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate from,

        @Schema(description = "조회 종료일 (포함)", example = "2026-10-31")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate to,

        @Schema(description = "유형 (CHARGE: 충전, PAYMENT: 결제). 없거나 빈 값이면 전체, 대소문자를 구분함",
                allowableValues = {"CHARGE", "PAYMENT"}, example = "PAYMENT")
        String type,

        @Schema(description = "페이지 번호 (0부터, 기본 0)", example = "0")
        @PositiveOrZero(message = "페이지 번호는 0 이상이어야 합니다.")
        Integer page,

        @Schema(description = "페이지 크기 (1~50, 기본 10)", example = "10")
        @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.")
        @Max(value = 50, message = "페이지 크기는 50 이하여야 합니다.")
        Integer size
) {
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 10;

    // 값을 안 보내면 기본값으로 채움
    public LedgerHistoryRequest {
        page = page == null ? DEFAULT_PAGE : page;
        size = size == null ? DEFAULT_SIZE : size;
    }
}
