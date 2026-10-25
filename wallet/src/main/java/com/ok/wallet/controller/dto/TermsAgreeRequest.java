package com.ok.wallet.controller.dto;

import com.ok.wallet.service.dto.TermsAgreeCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "약관 동의 요청")
public record TermsAgreeRequest(
        @Schema(description = "동의할 약관 목록 (필수 약관은 모두 포함해야 함)")
        @NotEmpty(message = "동의할 약관은 1개 이상이어야 합니다.")
        @Size(max = 10, message = "동의할 약관은 10개 이하여야 합니다.")
        List<@NotNull(message = "약관 항목은 비어 있을 수 없습니다.") @Valid TermsAgreeItemRequest> agreements
) {
    public List<TermsAgreeCommand> toCommands() {
        return agreements.stream()
                .map(item -> new TermsAgreeCommand(item.type(), item.version()))
                .toList();
    }
}
