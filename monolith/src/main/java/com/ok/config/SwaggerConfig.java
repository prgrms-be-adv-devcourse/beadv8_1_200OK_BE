package com.ok.config;

import com.ok.common.security.CurrentMemberId;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class SwaggerConfig {

    private static final String BEARER_AUTH = "bearerAuth";

    static {
        // @CurrentMemberId 파라미터는 토큰에서 채우므로 Swagger의 요청 파라미터로 노출하지 않는다.
        // 이 설정이 없으면 회원 id가 쿼리 파라미터 입력란으로 보여 직접 넣어야 하는 값처럼 오해된다.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentMemberId.class);
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("팔도곳간 API")
                        .description("paldo-gotgan 백엔드 API 문서")
                        .version("v1"))
                // Swagger UI의 Authorize 버튼에 로그인으로 받은 Access Token을 넣으면 모든 요청에 Bearer 헤더가 붙는다
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
