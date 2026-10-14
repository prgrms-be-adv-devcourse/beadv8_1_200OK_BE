package com.ok.member.auth.controller;

import com.ok.member.auth.controller.dto.LoginRequest;
import com.ok.member.auth.controller.dto.LoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Auth", description = "인증 API")
interface AuthControllerSpec {

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
    ResponseEntity<LoginResponse> login(LoginRequest request);

}
