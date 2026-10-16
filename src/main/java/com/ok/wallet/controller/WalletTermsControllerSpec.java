package com.ok.wallet.controller;

import com.ok.wallet.controller.dto.TermsAgreeRequest;
import com.ok.wallet.controller.dto.TermsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Wallet Terms", description = "지갑 충전 약관 API")
interface WalletTermsControllerSpec {
    String ALL_AGREE_EXAMPLE = """
            {
              "agreements": [
                {"type": "ELECTRONIC_FINANCIAL_TRANSACTION", "version": 1},
                {"type": "PREPAID_PAYMENT_INSTRUMENT", "version": 1},
                {"type": "PERSONAL_INFORMATION_COLLECTION", "version": 1}
              ]
            }
            """;

    String REQUIRED_MISSING_EXAMPLE = """
            {
              "agreements": [
                {"type": "ELECTRONIC_FINANCIAL_TRANSACTION", "version": 1},
                {"type": "PREPAID_PAYMENT_INSTRUMENT", "version": 1}
              ]
            }
            """;

    @Operation(summary = "충전 약관 조회", description = "현재 버전 약관 전문과 약관별 동의 여부를 반환한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요")
    })
    ResponseEntity<TermsResponse> getTerms(Long memberId);

    @Operation(summary = "충전 약관 동의",
            description = "필수 약관 전체를 현재 버전으로 보내야 저장된다. 이미 동의한 약관은 다시 저장하지 않아 재요청해도 성공한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "동의 성공 (이미 동의한 경우 포함)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "409", description = "약관이 개정되어 요청한 버전이 현재 버전이 아님"),
            @ApiResponse(responseCode = "422", description = "필수 약관 누락, 알 수 없는 약관 유형, 요청 값 검증 실패")
    })
    ResponseEntity<Void> agree(
            Long memberId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = {
                                    @ExampleObject(name = "필수 약관 전체 동의", value = ALL_AGREE_EXAMPLE),
                                    @ExampleObject(name = "필수 약관 누락 (422)", value = REQUIRED_MISSING_EXAMPLE)
                            }))
            TermsAgreeRequest request);
}
