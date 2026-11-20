package com.ok.member.seller.adapter.in.web;

import com.ok.member.seller.adapter.in.web.dto.StoreProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

/**
 * StoreProfileController의 Swagger 문서. 컨트롤러 코드와 문서 어노테이션을 분리하기 위해 인터페이스로 둔다.
 */
@Tag(name = "Store", description = "상점 API")
interface StoreProfileControllerSpec {

    @Operation(
            summary = "상점 프로필 조회",
            description = """
                    판매자가 등록한 상점 프로필을 반환합니다. 로그인 없이 누구나 호출할 수 있습니다.
                    상품 상세 응답의 sellerId로 호출해 '생산 정보' 아래와 상점 화면에 보여 줍니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "판매자가 상점 프로필을 아직 등록하지 않음")
    ResponseEntity<StoreProfileResponse> getStoreProfile(Long sellerId);

}
