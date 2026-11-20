package com.ok.carriersimulator.web.token;

import com.ok.carriersimulator.core.CarrierSimulator;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delivery Tracker의 토큰 발급(OAuth 2.0 client credentials)을 흉내 낸다.
 * 공식 문서에는 응답의 access_token만 나와 있어, 나머지 필드와 실패 응답은 OAuth 2.0 표준 형태를 따랐다.
 */
@RestController
public class TokenController {

    private final CarrierSimulator simulator;
    private final Clock clock;

    TokenController(CarrierSimulator simulator, Clock clock) {
        this.simulator = simulator;
        this.clock = clock;
    }

    // 아이디와 시크릿은 값을 검사하지 않고 비어 있는지만 본다. 시험용 서버라 아무 값이나 넣어 쓰게 한다
    @PostMapping(path = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Map<String, Object>> issue(
            @RequestParam(name = "grant_type", required = false) String grantType,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "client_secret", required = false) String clientSecret) {
        if (!"client_credentials".equals(grantType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported_grant_type"));
        }
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_client"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", simulator.issueToken(clock.instant()));
        body.put("token_type", "Bearer");
        body.put("expires_in", simulator.tokenTtlSeconds());
        return ResponseEntity.ok(body);
    }
}
