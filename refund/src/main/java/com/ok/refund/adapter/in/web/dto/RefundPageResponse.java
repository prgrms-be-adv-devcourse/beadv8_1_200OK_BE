package com.ok.refund.adapter.in.web.dto;

import com.ok.refund.application.port.in.RefundResult;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "환불 요청 목록")
public record RefundPageResponse(

        @Schema(description = "현재 페이지의 환불 요청 (최신순)")
        List<RefundResponse> content,

        @Schema(description = "현재 페이지 번호 (0부터)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "25")
        int size,

        @Schema(description = "전체 환불 요청 수", example = "3")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "1")
        int totalPages
) {

    // Spring Data의 Page를 그대로 직렬화하면 내부 구조(pageable, sort 등)가 응답 약속이 되므로 필요한 값만 담는다
    public static RefundPageResponse from(Page<RefundResult> page) {
        List<RefundResponse> content = new ArrayList<>();
        for (RefundResult result : page.getContent()) {
            content.add(RefundResponse.from(result));
        }
        return new RefundPageResponse(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
