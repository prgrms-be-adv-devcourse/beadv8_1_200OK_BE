package com.ok.member.seller.controller;

import com.ok.member.seller.controller.dto.SellerApplicationAdminPageResponse;
import com.ok.member.seller.controller.dto.SellerApplicationAdminResponse;
import com.ok.member.seller.controller.dto.SellerApplicationRejectRequest;
import com.ok.member.seller.controller.dto.SellerApplicationSearchRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * SellerApplicationAdminController의 Swagger 문서. 컨트롤러 코드와 문서 어노테이션을 분리하기 위해 인터페이스로 둔다.
 */
// 회원용 Seller 태그와 사용자 구분(관리자)이 달라 태그를 나눈다
@Tag(name = "Seller (Admin)", description = "관리자 판매자 신청 심사 API")
interface SellerApplicationAdminControllerSpec {

    @Operation(
            summary = "판매자 신청 목록 조회",
            description = """
                    판매자 신청을 오래된 신청부터 페이지 단위로 반환합니다. 먼저 신청한 순서대로 심사하기 위한 정렬입니다.
                    status로 상태를 거를 수 있고, 생략하면 모든 상태를 반환합니다. 기본은 0페이지, 20건입니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "관리자가 아님")
    @ApiResponse(responseCode = "422", description = "상태 값이 올바르지 않거나, 페이지 번호가 음수이거나 크기가 1~100 밖")
    ResponseEntity<SellerApplicationAdminPageResponse> getApplications(
            @ParameterObject SellerApplicationSearchRequest request
    );

    @Operation(
            summary = "판매자 신청 상세 조회",
            description = "신청 정보 전체(통신판매업 신고번호 포함)와 심사 진행 일시, 거절 사유, 승인·거절을 결정한 관리자를 반환합니다."
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "관리자가 아님")
    @ApiResponse(responseCode = "404", description = "신청이 없음")
    ResponseEntity<SellerApplicationAdminResponse> getApplication(
            @Parameter(description = "판매자 신청 id", example = "1") Long applicationId
    );

    @Operation(
            summary = "판매자 신청 심사 시작",
            description = "심사 대기(PENDING) 신청의 심사를 시작합니다(IN_REVIEW). 심사 시작 일시를 기록하고, 처리 후의 상세를 반환합니다."
    )
    @ApiResponse(responseCode = "200", description = "심사 시작 성공")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "관리자가 아님")
    @ApiResponse(responseCode = "404", description = "신청이 없음")
    @ApiResponse(responseCode = "409", description = "심사 대기가 아니거나, 다른 관리자가 먼저 처리함")
    ResponseEntity<SellerApplicationAdminResponse> startReview(
            @Parameter(description = "판매자 신청 id", example = "1") Long applicationId
    );

    @Operation(
            summary = "판매자 신청 승인",
            description = """
                    심사 중(IN_REVIEW) 신청을 승인합니다(APPROVED). 심사를 시작하지 않은 신청은 승인할 수 없습니다.
                    승인 일시와 승인한 관리자를 기록하고, 판매자 지갑이 만들어집니다. 처리 후의 상세를 반환합니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "승인 성공")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "관리자가 아님")
    @ApiResponse(responseCode = "404", description = "신청이 없음")
    // 같은 409 안에서 상태 충돌과 동시 처리를 구분해 안내해야 하므로 예시를 둔다
    @ApiResponse(responseCode = "409", description = "심사 중이 아니거나, 다른 관리자가 먼저 처리함",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = {
                            @ExampleObject(name = "현재 상태에서 처리할 수 없음", value = """
                                    {
                                      "type": "https://api.example.com/problems/member/invalid-seller-application-status",
                                      "title": "Invalid seller application status",
                                      "status": 409,
                                      "detail": "현재 신청 상태에서는 처리할 수 없습니다.",
                                      "instance": "/api/v1/admin/seller-applications/1/approve"
                                    }
                                    """),
                            @ExampleObject(name = "다른 관리자가 먼저 처리함", value = """
                                    {
                                      "type": "https://api.example.com/problems/member/seller-application-concurrent-modification",
                                      "title": "Seller application concurrent modification",
                                      "status": 409,
                                      "detail": "다른 관리자가 먼저 처리했습니다. 새로고침 후 다시 확인해 주세요.",
                                      "instance": "/api/v1/admin/seller-applications/1/approve"
                                    }
                                    """)
                    }))
    ResponseEntity<SellerApplicationAdminResponse> approve(
            Long adminId,
            @Parameter(description = "판매자 신청 id", example = "1") Long applicationId
    );

    @Operation(
            summary = "판매자 신청 거절",
            description = """
                    심사 대기(PENDING)나 심사 중(IN_REVIEW) 신청을 거절합니다(REJECTED). 거절 사유는 필수입니다.
                    거절 일시와 사유, 거절한 관리자를 기록합니다. 사유는 신청한 회원의 신청 내역에 그대로 보입니다.
                    거절된 회원은 대기 기간 없이 바로 다시 신청할 수 있습니다. 처리 후의 상세를 반환합니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "거절 성공")
    @ApiResponse(responseCode = "400", description = "요청 본문을 읽을 수 없음 (잘못된 JSON 형식)")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "관리자가 아님")
    @ApiResponse(responseCode = "404", description = "신청이 없음")
    @ApiResponse(responseCode = "409", description = "심사 대기·심사 중이 아니거나, 다른 관리자가 먼저 처리함")
    @ApiResponse(responseCode = "422", description = "거절 사유가 비었거나 500자를 넘음")
    ResponseEntity<SellerApplicationAdminResponse> reject(
            Long adminId,
            @Parameter(description = "판매자 신청 id", example = "1") Long applicationId,
            SellerApplicationRejectRequest request
    );

}
