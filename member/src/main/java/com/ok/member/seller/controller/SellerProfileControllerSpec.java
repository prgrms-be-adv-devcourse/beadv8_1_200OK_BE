package com.ok.member.seller.controller;

import com.ok.member.seller.controller.dto.SellerProfileRequest;
import com.ok.member.seller.controller.dto.SellerProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

/**
 * SellerProfileController의 Swagger 문서. 컨트롤러 코드와 문서 어노테이션을 분리하기 위해 인터페이스로 둔다.
 */
@Tag(name = "Seller", description = "판매자 API")
interface SellerProfileControllerSpec {

    @Operation(summary = "내 판매자 프로필 조회", description = "로그인한 판매자 본인의 상점 프로필을 반환합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "판매자 권한(SELLER)이 없음. 승인 직후라면 토큰을 재발급받고 다시 시도")
    @ApiResponse(responseCode = "404", description = "프로필을 아직 등록하지 않음")
    ResponseEntity<SellerProfileResponse> getMyProfile(Long memberId);

    @Operation(
            summary = "판매자 프로필 등록",
            description = """
                    판매자(SELLER 권한)가 상점 프로필을 등록합니다. 한 회원은 프로필(상점)을 하나만 가질 수 있습니다.
                    프로필 사진은 먼저 POST /api/v1/media/SELLER_PROFILE로 올리고, 응답의 url을 profileImageUrl로 보냅니다.
                    """
    )
    @ApiResponse(responseCode = "201", description = "등록 성공")
    @ApiResponse(responseCode = "400", description = "요청 본문을 읽을 수 없음 (잘못된 JSON 형식)")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "판매자 권한(SELLER)이 없음. 승인 직후라면 토큰을 재발급받고 다시 시도")
    @ApiResponse(responseCode = "409", description = "이미 프로필을 등록함 (수정 API를 사용)")
    @ApiResponse(responseCode = "422", description = "요청 값 검증 실패, 또는 판매자 프로필용으로 업로드한 사진 주소가 아님")
    ResponseEntity<SellerProfileResponse> register(Long memberId, SellerProfileRequest request);

    @Operation(
            summary = "판매자 프로필 수정",
            description = """
                    판매자 본인의 상점 프로필 전체를 요청 값으로 바꿉니다.
                    profileImageUrl을 비우거나 null로 보내면 프로필 사진을 지웁니다(화면에서 상호 첫 글자를 보여 줍니다).
                    """
    )
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "400", description = "요청 본문을 읽을 수 없음 (잘못된 JSON 형식)")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "판매자 권한(SELLER)이 없음. 승인 직후라면 토큰을 재발급받고 다시 시도")
    @ApiResponse(responseCode = "404", description = "프로필을 아직 등록하지 않음 (등록 API를 사용)")
    @ApiResponse(responseCode = "422", description = "요청 값 검증 실패, 또는 판매자 프로필용으로 업로드한 사진 주소가 아님")
    ResponseEntity<SellerProfileResponse> update(Long memberId, SellerProfileRequest request);

}
