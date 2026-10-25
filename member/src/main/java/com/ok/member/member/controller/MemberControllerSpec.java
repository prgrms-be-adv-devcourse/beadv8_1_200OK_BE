package com.ok.member.member.controller;

import com.ok.member.member.controller.dto.MemberInfoResponse;
import com.ok.member.member.controller.dto.MemberInfoUpdateRequest;
import com.ok.member.member.controller.dto.SignupRequest;
import com.ok.member.member.controller.dto.SignupResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * MemberController의 Swagger 문서. 컨트롤러 코드와 문서 어노테이션을 분리하기 위해 인터페이스로 둔다.
 */
@Tag(name = "Member", description = "회원 API")
interface MemberControllerSpec {

    // 토큰이 필요 없는 API라 Swagger의 전역 Bearer 인증(자물쇠 표시)에서 제외한다
    @SecurityRequirements
    @Operation(
            summary = "일반 회원가입",
            description = """
                    일반 회원(USER)으로 가입합니다. 가입한 회원은 구매할 수 있고, 판매하려면 판매자 신청·승인을 거칩니다.
                    이용약관 동의와 개인정보 수집·이용 동의는 필수입니다.
                    가입이 완료되면 MemberRegisteredEvent가 발행됩니다.
                    """
    )
    @ApiResponse(responseCode = "201", description = "회원가입 성공")
    @ApiResponse(responseCode = "400", description = "요청 본문을 읽을 수 없음 (잘못된 JSON 형식)")
    // 같은 409 안에서 아이디 중복과 이메일 중복을 구분해야 하므로 예시를 둔다
    @ApiResponse(responseCode = "409", description = "아이디 또는 이메일 중복",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = {
                            @ExampleObject(name = "중복 아이디", value = """
                                    {
                                      "type": "https://api.example.com/problems/member/duplicate-login-id",
                                      "title": "Duplicate login id",
                                      "status": 409,
                                      "detail": "이미 사용 중인 아이디입니다.",
                                      "instance": "/api/v1/members/signup"
                                    }
                                    """),
                            @ExampleObject(name = "중복 이메일", value = """
                                    {
                                      "type": "https://api.example.com/problems/member/duplicate-email",
                                      "title": "Duplicate email",
                                      "status": 409,
                                      "detail": "이미 사용 중인 이메일입니다.",
                                      "instance": "/api/v1/members/signup"
                                    }
                                    """)
                    }))
    @ApiResponse(responseCode = "422", description = "요청 값 검증 실패")
    ResponseEntity<SignupResponse> signup(SignupRequest request);

    @Operation(summary = "내 정보 조회", description = "로그인한 회원 본인의 아이디, 이메일, 이름, 닉네임을 반환합니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "404", description = "회원이 없거나 탈퇴함")
    ResponseEntity<MemberInfoResponse> getMyInfo(Long memberId);

    @Operation(
            summary = "내 정보 수정",
            description = """
                    로그인한 회원 본인의 이름과 닉네임을 요청 값으로 바꿉니다. 두 값을 모두 보내야 합니다.
                    아이디와 이메일은 바꿀 수 없습니다. 닉네임은 다른 회원과 같아도 됩니다.
                    """
    )
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "400", description = "요청 본문을 읽을 수 없음 (잘못된 JSON 형식)")
    @ApiResponse(responseCode = "401", description = "로그인 필요")
    @ApiResponse(responseCode = "404", description = "회원이 없거나 탈퇴함")
    @ApiResponse(responseCode = "422", description = "요청 값 검증 실패")
    ResponseEntity<MemberInfoResponse> updateMyInfo(Long memberId, MemberInfoUpdateRequest request);

}
