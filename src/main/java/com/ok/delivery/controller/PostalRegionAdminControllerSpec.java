package com.ok.delivery.controller;

import com.ok.delivery.controller.dto.PostalRegionPageRequest;
import com.ok.delivery.controller.dto.PostalRegionPageResponse;
import com.ok.delivery.controller.dto.PostalRegionRegisterRequest;
import com.ok.delivery.controller.dto.PostalRegionResponse;
import com.ok.delivery.controller.dto.PostalRegionUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;

@Tag(name = "Postal Region Admin", description = "도서산간 우편번호 관리자 API")
interface PostalRegionAdminControllerSpec {

    @Operation(summary = "도서산간 우편번호 등록",
            description = "삭제된 우편번호를 다시 등록하면 요청한 그룹과 소요일로 되살린다. 등록·되살림은 변경 이력에 남는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "관리자가 아님"),
            @ApiResponse(responseCode = "409", description = "이미 등록된 우편번호 (POSTAL_REGION_DUPLICATED)"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패, 없는 지역 그룹이거나 GENERAL (REGION_GROUP_NOT_FOUND)")
    })
    ResponseEntity<PostalRegionResponse> register(Long adminId, PostalRegionRegisterRequest request);

    @Operation(summary = "도서산간 우편번호 수정",
            description = "지역 그룹과 추가 소요일을 바꾼다. 삭제 후 재등록과 달리 일반 지역으로 판정되는 순간 없이 바로 반영된다. "
                    + "바뀐 값은 변경 이력에 남고, 같은 값이면 이력을 남기지 않는다. 삭제된 우편번호는 등록 API로 되살린다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "관리자가 아님"),
            @ApiResponse(responseCode = "404", description = "없거나 삭제된 우편번호 (POSTAL_REGION_NOT_FOUND)"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패, 없는 지역 그룹이거나 GENERAL (REGION_GROUP_NOT_FOUND)")
    })
    ResponseEntity<PostalRegionResponse> update(
            Long adminId,
            @Parameter(description = "우편번호 (숫자 5자리)", example = "63000") String postalCode,
            PostalRegionUpdateRequest request
    );

    @Operation(summary = "도서산간 우편번호 삭제",
            description = "soft delete다. 삭제한 우편번호는 일반 지역으로 판정되고, 삭제는 변경 이력에 남는다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "관리자가 아님"),
            @ApiResponse(responseCode = "404", description = "없거나 이미 삭제된 우편번호 (POSTAL_REGION_NOT_FOUND)")
    })
    ResponseEntity<Void> delete(Long adminId, @Parameter(description = "우편번호 (숫자 5자리)", example = "63000") String postalCode);

    @Operation(summary = "도서산간 우편번호 목록", description = "삭제되지 않은 우편번호를 우편번호 순으로 조회한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "관리자가 아님"),
            @ApiResponse(responseCode = "422", description = "페이지 번호가 음수이거나 크기가 1~100 밖")
    })
    ResponseEntity<PostalRegionPageResponse> getPostalRegions(@ParameterObject PostalRegionPageRequest request);
}
