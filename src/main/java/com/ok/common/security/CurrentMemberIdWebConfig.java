package com.ok.common.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * {@link CurrentMemberId} 파라미터 리졸버를 Spring MVC에 등록한다.
 */
@Configuration(proxyBeanMethods = false)
public class CurrentMemberIdWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentMemberIdArgumentResolver());
    }

}
