package com.ok.member.seller.controller;

import com.ok.member.seller.controller.dto.SellerApplicationHistoryResponse;
import com.ok.member.seller.controller.dto.SellerApplicationResponse;
import com.ok.member.seller.controller.dto.SellerApplyRequest;
import com.ok.member.seller.controller.dto.SellerApplyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * SellerApplicationController의 Swagger 문서. 컨트롤러 코드와 문서 어노테이션을 분리하기 위해 인터페이스로 둔다.
 */
@Tag(name = "Seller", description = "판매자 API")
interface SellerApplicationControllerSpec {

    @Operation(
            summary = "판매자 등록 신청",
            description = """
                    로그인한 일반 회원이 판매자 등록을 신청합니다. 신청은 심사 대기(PENDING) 상태로 저장되고, 관리자가 확인한 뒤 승인합니다.
                    한 회원은 상점을 하나만 가질 수 있어, 심사 대기·심사 중·승인된 신청이 있으면 새로 신청할 수 없습니다.
                    거절된 신청만 있으면 대기 기간 없이 바로 다시 신청할 수 있습니다.
                    한 사업자등록번호로는 한 번만 신청할 수 있습니다(거절된 신청은 제외).
                    승인 전에도 구매는 그대로 할 수 있습니다.
                    """
    )
    @ApiResponse(responseCode = "201", description = "신청 성공")
    @ApiResponse(responseCode = "400", description = "요청 본문을 읽을 수 없음 (잘못된 JSON 형식)")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "일반 회원이 아님 (관리자 계정)")
    // 같은 409 안에서 진행 중 신청, 이미 승인, 사업자등록번호 중복을 구분해 안내해야 하므로 예시를 둔다
    @ApiResponse(responseCode = "409", description = "진행 중인 신청이 있거나, 이미 판매자이거나, 사업자등록번호 중복",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = {
                            @ExampleObject(name = "진행 중인 신청", value = """
                                    {
                                      "type": "https://api.example.com/problems/member/seller-application-in-progress",
                                      "title": "Seller application in progress",
                                      "status": 409,
                                      "detail": "심사 중인 판매자 신청이 있습니다.",
                                      "instance": "/api/v1/sellers/me/applications"
                                    }
                                    """),
                            @ExampleObject(name = "이미 판매자", value = """
                                    {
                                      "type": "https://api.example.com/problems/member/already-seller",
                                      "title": "Already seller",
                                      "status": 409,
                                      "detail": "이미 판매자로 승인된 회원입니다.",
                                      "instance": "/api/v1/sellers/me/applications"
                                    }
                                    """),
                            @ExampleObject(name = "사업자등록번호 중복", value = """
                                    {
                                      "type": "https://api.example.com/problems/member/duplicate-business-number",
                                      "title": "Duplicate business number",
                                      "status": 409,
                                      "detail": "이미 신청된 사업자등록번호입니다.",
                                      "instance": "/api/v1/sellers/me/applications"
                                    }
                                    """)
                    }))
    @ApiResponse(responseCode = "422", description = "요청 값 검증 실패")
    ResponseEntity<SellerApplyResponse> apply(Long memberId, SellerApplyRequest request);

    @Operation(
            summary = "내 판매자 신청 내역 조회",
            description = """
                    로그인한 회원의 판매자 신청 내역을 최근 신청부터 반환합니다.
                    각 신청의 상태와 신청일, 심사 시작일, 승인일(거절이면 거절일과 거절 사유)을 보여 줍니다. 거절된 신청도 이력으로 포함합니다.
                    신청한 적이 없으면 빈 목록입니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "일반 회원이 아님 (관리자 계정)")
    ResponseEntity<SellerApplicationHistoryResponse> getMyApplications(Long memberId);

    @Operation(
            summary = "내 판매자 신청 정보 조회",
            description = "로그인한 회원 본인의 신청 한 건의 상호명, 대표자명, 사업자등록번호, 신청일을 반환합니다."
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "일반 회원이 아님 (관리자 계정)")
    @ApiResponse(responseCode = "404", description = "신청이 없거나 본인의 신청이 아님")
    ResponseEntity<SellerApplicationResponse> getMyApplication(
            Long memberId,
            @Parameter(description = "판매자 신청 id", example = "1") Long applicationId
    );

}
