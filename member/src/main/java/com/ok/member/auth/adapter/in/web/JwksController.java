package com.ok.member.auth.adapter.in.web;

import com.ok.common.config.JwtConfig;
import com.ok.common.config.JwtProperties;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 토큰 검증에 쓰는 공개키 목록을 내준다.
 * 다른 모듈 앱이 {@code jwt.jwk-set-uri}로 이 주소를 지정하면 공개키 파일 없이 여기서 받은 키로 토큰을 검증하므로,
 * 키를 교체할 때 이 모듈의 설정만 바꾸면 된다.
 *
 * <p>경로는 {@code /api/v1}이 아니라 JWKS 관례 경로를 쓴다. 다른 앱이 서버끼리 호출하는 주소라 nginx가 외부에 공개하지 않고,
 * 공개키는 비밀이 아니므로 인증 없이 허용한다({@code SecurityConfig}). 클라이언트용 API가 아니라 API 문서에도 넣지 않는다.
 *
 * <p>응답은 표준 형식({@code {"keys": [...]}})을 그대로 내보내려고 Nimbus가 만든 JSON 객체를 쓴다.
 */
@Hidden
@RestController
public class JwksController {

    private final Map<String, Object> publicJwkSet;
    private final CacheControl cacheControl;

    public JwksController(JwtProperties properties) {
        // 키 목록은 기동 시점 설정으로 정해지고 실행 중에 바뀌지 않는다. 키 교체는 설정을 바꾸고 재시작한다.
        // verificationKeys()는 공개키만 담지만, 나중에 개인키가 섞이더라도 응답에 나가지 않도록 공개 부분만 다시 거른다
        this.publicJwkSet = JwtConfig.verificationKeys(properties).toPublicJWKSet().toJSONObject();
        // 검증 앱의 캐시 시간과 같은 값으로 알린다
        this.cacheControl = CacheControl.maxAge(properties.jwkSet().cacheTtl());
    }

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> getJwks() {
        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .body(publicJwkSet);
    }

}
