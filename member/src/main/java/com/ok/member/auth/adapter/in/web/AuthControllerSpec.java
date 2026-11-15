package com.ok.member.auth.adapter.in.web;

import com.ok.member.auth.adapter.in.web.dto.LoginRequest;
import com.ok.member.auth.adapter.in.web.dto.LogoutRequest;
import com.ok.member.auth.adapter.in.web.dto.TokenReissueRequest;
import com.ok.member.auth.adapter.in.web.dto.TokenResponse;
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

    // Access Token이 만료된 뒤에도 로그아웃할 수 있도록 토큰 없이 호출한다. 헤더에 토큰이 있어도 검사하지 않는다
    @SecurityRequirements
    @Operation(
            summary = "로그아웃",
            description = """
                    Refresh Token의 세션을 끝냅니다. 이후 그 Refresh Token으로는 재발급할 수 없습니다.
                    같은 회원이 다른 기기에서 로그인한 세션은 그대로 유지됩니다.
                    없거나 만료되었거나 이미 로그아웃한 토큰이어도 204를 반환하므로, 같은 요청을 다시 보내도 됩니다.
                    이미 발급된 Access Token은 만료될 때까지 유효하므로 클라이언트에서 함께 지워야 합니다.
                    진행 중인 재발급 요청이 있으면 그 응답을 기다렸다가, 응답으로 받은 새 Refresh Token으로 로그아웃해야 합니다.
                    옛 토큰으로 보내면 재발급이 먼저 처리된 경우 서버가 세션을 찾지 못해 새 토큰의 세션이 남습니다.
                    재발급이 실패했으면 가지고 있던 토큰으로 보냅니다.
                    """
    )
    @ApiResponse(responseCode = "204", description = "로그아웃 성공 (토큰이 없거나 만료된 경우 포함)")
    @ApiResponse(responseCode = "422", description = "요청 값 검증 실패")
    ResponseEntity<Void> logout(LogoutRequest request);

}
