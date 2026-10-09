package com.ok.member.member.controller;

import com.ok.member.member.controller.dto.SignupRequest;
import com.ok.member.member.controller.dto.SignupResponse;
import com.ok.member.member.service.MemberSignupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class ApiV1MemberController implements MemberControllerDocs {

    private final MemberSignupService memberSignupService;

    @Override
    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(
            @Valid @RequestBody SignupRequest request
    ) {
        Long memberId = memberSignupService.signup(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SignupResponse(memberId));
    }
}
