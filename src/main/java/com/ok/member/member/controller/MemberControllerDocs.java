package com.ok.member.member.controller;

import com.ok.member.member.controller.dto.SignupRequest;
import com.ok.member.member.controller.dto.SignupResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * MemberController의 Swagger 문서. 컨트롤러 코드와 문서 어노테이션을 분리하기 위해 인터페이스로 둔다.
 */
@Tag(name = "Member", description = "회원  API")
public interface MemberControllerDocs {

    @Operation(
            summary = "일반 회원가입",
            description = """
                    구매자(BUYER) 권한의 일반 회원으로 가입합니다.
                    이용약관 동의와 개인정보 수집·이용 동의는 필수입니다.
                    가입이 완료되면 MemberRegisteredEvent가 발행됩니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "회원가입 성공",
                    content = @Content(schema = @Schema(implementation = SignupResponse.class))),
            @ApiResponse(responseCode = "400", description = "요청 본문을 읽을 수 없음 (잘못된 JSON 형식)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
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
                                              "instance": "/api/members/signup"
                                            }
                                            """),
                                    @ExampleObject(name = "중복 이메일", value = """
                                            {
                                              "type": "https://api.example.com/problems/member/duplicate-email",
                                              "title": "Duplicate email",
                                              "status": 409,
                                              "detail": "이미 사용 중인 이메일입니다.",
                                              "instance": "/api/members/signup"
                                            }
                                            """)
                            })),
            @ApiResponse(responseCode = "422", description = "요청 값 검증 실패",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "검증 실패", value = """
                                    {
                                      "type": "https://api.example.com/problems/validation-failed",
                                      "title": "Validation failed",
                                      "status": 422,
                                      "detail": "요청 값이 올바르지 않습니다.",
                                      "instance": "/api/members/signup",
                                      "errors": [
                                        {
                                          "detail": "이메일 형식이 올바르지 않습니다.",
                                          "pointer": "/email",
                                          "field": "email"
                                        },
                                        {
                                          "detail": "이용약관 동의는 필수입니다.",
                                          "pointer": "/termsAgreed",
                                          "field": "termsAgreed"
                                        }
                                      ]
                                    }
                                    """)))
    })
    ResponseEntity<SignupResponse> signup(SignupRequest request);

}
