package com.ok.member.seller.controller;

import com.ok.member.seller.controller.dto.SellerApplicationAdminPageResponse;
import com.ok.member.seller.controller.dto.SellerApplicationAdminResponse;
import com.ok.member.seller.controller.dto.SellerApplicationSearchRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
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

}
