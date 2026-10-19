package com.ok.member.member.config;

import com.ok.member.member.service.AdminAccountService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * 최초 관리자 계정 생성을 켠다. 관리자 아이디({@code ADMIN_BOOTSTRAP_LOGIN_ID})가 설정됐을 때만 등록된다.
 *
 * <p>설정값은 이 설정이 등록될 때만 바인딩한다. 그래서 아이디를 비워 두면 비밀번호·이메일이 없어도 앱이 그대로 뜨고,
 * 아이디를 넣었으면 나머지 값이 빠졌을 때 시작이 막힌다({@link AdminBootstrapProperties}).
 * 테스트 설정에는 이 값이 없어 테스트 실행 중에는 동작하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(AdminBootstrapConfig.LoginIdConfigured.class)
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminBootstrapConfig {

    @Bean
    AdminBootstrapRunner adminBootstrapRunner(AdminAccountService adminAccountService,
                                              AdminBootstrapProperties properties) {
        return new AdminBootstrapRunner(adminAccountService, properties);
    }

    /**
     * 관리자 아이디가 비어 있지 않을 때만 참이다.
     * {@code @ConditionalOnProperty}는 값이 빈 문자열이어도 설정된 것으로 보므로,
     * {@code .env.example}처럼 {@code ADMIN_BOOTSTRAP_LOGIN_ID=}로 비워 둔 경우를 꺼진 것으로 다루려고 직접 확인한다.
     */
    static class LoginIdConfigured implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty("admin-bootstrap.login-id"));
        }

    }

}
