package com.ok.order.cart.adapter.in.web;

import com.ok.order.cart.adapter.in.web.dto.AddCartItemRequest;
import com.ok.order.cart.adapter.in.web.dto.AddCartItemResponse;
import com.ok.order.cart.adapter.in.web.dto.CartResponse;
import com.ok.order.cart.adapter.in.web.dto.UpdateCartItemQuantityRequest;
import com.ok.order.cart.adapter.in.web.dto.UpdateCartItemSelectionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Cart", description = "장바구니 API")
interface CartControllerSpec {

    @Operation(summary = "장바구니 상품 담기", description = "이미 담긴 옵션이면 수량을 더합니다(최대 99개).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "새로 담음"),
            @ApiResponse(responseCode = "200", description = "이미 담긴 옵션이라 수량을 더함"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "구매 권한 없음 (관리자 등)"),
            @ApiResponse(responseCode = "400", description = "본인이 판매하는 상품 또는 수량이 1~99가 아님"),
            @ApiResponse(responseCode = "404", description = "장바구니 또는 상품 옵션 없음"),
            @ApiResponse(responseCode = "409", description = "판매 중이 아닌 상품"),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패 (수량 1~99 등)")
    })
    ResponseEntity<AddCartItemResponse> addItem(Long memberId, AddCartItemRequest request);

    @Operation(summary = "내 장바구니 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "구매 권한 없음 (관리자 등)"),
            @ApiResponse(responseCode = "404", description = "장바구니 없음")
    })
    ResponseEntity<CartResponse> getMyCart(Long memberId);

    @Operation(summary = "장바구니 상품 수량 변경")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "변경 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "구매 권한 없음 (관리자 등)"),
            @ApiResponse(responseCode = "404", description = "장바구니 또는 장바구니 상품 없음 (다른 회원의 상품 포함)"),
            @ApiResponse(responseCode = "400", description = "요청 본문 형식 오류"),
            @ApiResponse(responseCode = "422", description = "수량이 1개 미만이거나 누락")
    })
    ResponseEntity<Void> changeQuantity(Long memberId, Long cartItemId, UpdateCartItemQuantityRequest request);

    @Operation(summary = "장바구니 상품 선택·해제")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "변경 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "구매 권한 없음 (관리자 등)"),
            @ApiResponse(responseCode = "404", description = "장바구니 또는 장바구니 상품 없음 (다른 회원의 상품 포함)"),
            @ApiResponse(responseCode = "422", description = "선택 여부 누락")
    })
    ResponseEntity<Void> changeSelected(Long memberId, Long cartItemId, UpdateCartItemSelectionRequest request);

    @Operation(summary = "장바구니 상품 전체 선택·해제")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "변경 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "구매 권한 없음 (관리자 등)"),
            @ApiResponse(responseCode = "404", description = "장바구니 없음"),
            @ApiResponse(responseCode = "422", description = "선택 여부 누락")
    })
    ResponseEntity<Void> changeAllSelected(Long memberId, UpdateCartItemSelectionRequest request);

    @Operation(summary = "장바구니 상품 삭제")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "구매 권한 없음 (관리자 등)"),
            @ApiResponse(responseCode = "404", description = "장바구니 또는 장바구니 상품 없음 (다른 회원의 상품 포함)")
    })
    ResponseEntity<Void> deleteItem(Long memberId, Long cartItemId);

    @Operation(summary = "선택한 장바구니 상품 삭제")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 필요"),
            @ApiResponse(responseCode = "403", description = "구매 권한 없음 (관리자 등)"),
            @ApiResponse(responseCode = "404", description = "장바구니 없음")
    })
    ResponseEntity<Void> deleteSelectedItems(Long memberId);
}