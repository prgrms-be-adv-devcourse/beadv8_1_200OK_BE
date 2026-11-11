package com.ok.member.auth.adapter.in.web;

import com.ok.member.auth.adapter.in.web.dto.LoginRequest;
import com.ok.member.auth.adapter.in.web.dto.TokenReissueRequest;
import com.ok.member.auth.adapter.in.web.dto.TokenResponse;
import com.ok.member.auth.application.dto.TokenResult;
import com.ok.member.auth.application.port.in.LoginUseCase;
import com.ok.member.auth.application.port.in.TokenReissueUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController implements AuthControllerSpec {

    private final LoginUseCase loginUseCase;
    private final TokenReissueUseCase tokenReissueUseCase;

    @Override
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenResult result = loginUseCase.login(request.toCommand());
        return ResponseEntity.ok(TokenResponse.from(result));
    }

    @Override
    @PostMapping("/reissue")
    public ResponseEntity<TokenResponse> reissue(@Valid @RequestBody TokenReissueRequest request) {
        TokenResult result = tokenReissueUseCase.reissue(request.toCommand());
        return ResponseEntity.ok(TokenResponse.from(result));
    }

}
