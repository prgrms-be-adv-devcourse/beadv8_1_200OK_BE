package com.ok.member.auth.controller;

import com.ok.member.auth.controller.dto.LoginRequest;
import com.ok.member.auth.controller.dto.LoginResponse;
import com.ok.member.auth.service.AuthService;
import com.ok.member.auth.service.dto.LoginResult;
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
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authService.login(request.toCommand());
        return ResponseEntity.ok(LoginResponse.from(result));
    }

}
