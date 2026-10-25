package com.ok.member.auth.controller;

import com.ok.member.auth.controller.dto.LoginRequest;
import com.ok.member.auth.controller.dto.TokenReissueRequest;
import com.ok.member.auth.controller.dto.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Auth", description = "인증 API")
interface AuthControllerSpec {

    // 토큰이 필요 없는 API라 Swagger의 전역 Bearer 인증(자물쇠 표시)에서 제외한다
    @SecurityRequirements
    @Operation(
            summary = "로그인",
            description = """
                    로그인 아이디와 비밀번호로 로그인하고 Access Token과 Refresh Token을 발급합니다.
                    아이디는 대소문자를 구분하지 않습니다.
                    아이디가 없는 경우와 비밀번호가 틀린 경우는 같은 응답(401)을 반환합니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "로그인 성공")
    @ApiResponse(responseCode = "401", description = "아이디 또는 비밀번호 불일치")
    @ApiResponse(responseCode = "403", description = "이용이 정지된 회원")
    @ApiResponse(responseCode = "422", description = "요청 값 검증 실패")
    ResponseEntity<TokenResponse> login(LoginRequest request);

    // Access Token이 만료된 뒤에 부르는 API라 토큰 없이 호출한다
    @SecurityRequirements
    @Operation(
            summary = "토큰 재발급",
            description = """
                    Refresh Token으로 새 Access Token과 Refresh Token을 발급합니다.
                    Access Token이 만료되어 401(token-expired)을 받았을 때 호출합니다.
                    사용한 Refresh Token은 다시 쓸 수 없으므로, 응답의 새 Refresh Token으로 교체해 저장해야 합니다.
                    회원의 최신 권한으로 발급하므로, 판매자 승인 같은 권한 변경도 재로그인 없이 반영됩니다.
                    실패하면(401) 다시 로그인해야 합니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "재발급 성공")
    @ApiResponse(responseCode = "401", description = "없거나 만료되었거나 이미 사용한 Refresh Token, 또는 탈퇴한 회원 (다시 로그인 필요)")
    @ApiResponse(responseCode = "403", description = "이용이 정지된 회원")
    @ApiResponse(responseCode = "422", description = "요청 값 검증 실패")
    ResponseEntity<TokenResponse> reissue(TokenReissueRequest request);

}
