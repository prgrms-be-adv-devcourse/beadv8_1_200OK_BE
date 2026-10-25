package com.ok.member.auth.controller;

import com.ok.member.auth.controller.dto.LoginRequest;
import com.ok.member.auth.controller.dto.TokenReissueRequest;
import com.ok.member.auth.controller.dto.TokenResponse;
import com.ok.member.auth.service.AuthService;
import com.ok.member.auth.service.dto.TokenResult;
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

    private final AuthService authService;

    @Override
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenResult result = authService.login(request.toCommand());
        return ResponseEntity.ok(TokenResponse.from(result));
    }

    @Override
    @PostMapping("/reissue")
    public ResponseEntity<TokenResponse> reissue(@Valid @RequestBody TokenReissueRequest request) {
        TokenResult result = authService.reissue(request.toCommand());
        return ResponseEntity.ok(TokenResponse.from(result));
    }

}
