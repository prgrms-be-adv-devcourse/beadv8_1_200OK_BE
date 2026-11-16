package com.ok.member.member.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.member.member.adapter.in.web.dto.MemberInfoResponse;
import com.ok.member.member.adapter.in.web.dto.MemberInfoUpdateRequest;
import com.ok.member.member.adapter.in.web.dto.SignupRequest;
import com.ok.member.member.adapter.in.web.dto.SignupResponse;
import com.ok.member.member.application.port.in.MemberInfoUseCase;
import com.ok.member.member.application.port.in.MemberSignupUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController implements MemberControllerSpec {

    private final MemberSignupUseCase memberSignupUseCase;
    private final MemberInfoUseCase memberInfoUseCase;

    @Override
    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(
            @Valid @RequestBody SignupRequest request
    ) {
        Long memberId = memberSignupUseCase.signup(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SignupResponse(memberId));
    }

    @Override
    @GetMapping("/me")
    public ResponseEntity<MemberInfoResponse> getMyInfo(@CurrentMemberId Long memberId) {
        return ResponseEntity.ok(MemberInfoResponse.from(memberInfoUseCase.getMyInfo(memberId)));
    }

    @Override
    @PutMapping("/me")
    public ResponseEntity<MemberInfoResponse> updateMyInfo(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody MemberInfoUpdateRequest request
    ) {
        return ResponseEntity.ok(MemberInfoResponse.from(memberInfoUseCase.updateMyInfo(memberId, request.toCommand())));
    }

}
