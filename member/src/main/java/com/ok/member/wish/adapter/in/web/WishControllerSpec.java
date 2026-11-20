package com.ok.member.wish.adapter.in.web;

import com.ok.member.wish.adapter.in.web.dto.WishPageRequest;
import com.ok.member.wish.adapter.in.web.dto.WishPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;

/**
 * WishController의 Swagger 문서. 컨트롤러 코드와 문서 어노테이션을 분리하기 위해 인터페이스로 둔다.
 */
@Tag(name = "Wish", description = "관심상품(찜) API")
interface WishControllerSpec {

    @Operation(
            summary = "관심상품 등록",
            description = """
                    상품을 찜합니다. 여러 번 보내도 결과가 같습니다.
                    이미 찜한 상품이면 아무것도 바꾸지 않고, 해제했던 상품이면 지금 시각으로 다시 찜해 목록 맨 앞에 옵니다.
                    상품이 있는지는 확인하지 않습니다. 없는 상품은 목록에는 남지만 상품 카드 조회에서 빠져 화면에 보이지 않습니다.
                    """
    )
    @ApiResponse(responseCode = "204", description = "찜함 (이미 찜한 상품 포함)")
    @ApiResponse(responseCode = "400", description = "상품 ID가 1 이상의 숫자가 아님")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "구매 권한(BUYER)이 없음 (관리자 계정)")
    ResponseEntity<Void> wish(
            Long memberId,
            @Parameter(description = "찜할 상품 ID", example = "101") @Positive Long productId
    );

    @Operation(summary = "관심상품 해제", description = "찜을 해제합니다. 찜하지 않았거나 이미 해제한 상품이어도 204를 반환합니다.")
    @ApiResponse(responseCode = "204", description = "해제함 (찜하지 않은 상품 포함)")
    @ApiResponse(responseCode = "400", description = "상품 ID가 1 이상의 숫자가 아님")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "구매 권한(BUYER)이 없음 (관리자 계정)")
    ResponseEntity<Void> unwish(
            Long memberId,
            @Parameter(description = "해제할 상품 ID", example = "101") @Positive Long productId
    );

    @Operation(
            summary = "내 관심상품 목록",
            description = """
                    해제하지 않은 내 찜을 최근에 찜한 순서로 반환합니다. 상품 ID와 찜한 시각만 담습니다.
                    상품 카드(이름, 가격 등)는 상품 카드 여러 개 조회 API로 받아 붙입니다.
                    숨김·삭제된 상품은 카드 조회에서 빠지므로, 화면에 보이는 카드가 size보다 적을 수 있습니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "403", description = "구매 권한(BUYER)이 없음 (관리자 계정)")
    @ApiResponse(responseCode = "422", description = "페이지 번호나 크기가 범위를 벗어남")
    ResponseEntity<WishPageResponse> getMyWishes(Long memberId, @Valid WishPageRequest request);

}
