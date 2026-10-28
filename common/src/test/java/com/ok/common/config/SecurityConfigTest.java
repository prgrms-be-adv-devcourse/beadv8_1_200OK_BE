package com.ok.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void 개발_테스트_프로필에서는_API_문서를_연다(String profile) {
        // given
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);

        // when & then
        assertThat(SecurityConfig.isApiDocsOpen(environment)).isTrue();
    }

    // 막을 프로필이 아니라 열 프로필을 적으므로, 운영 프로필 이름이 바뀌거나 새 프로필이 생겨도 열리지 않는다
    @ParameterizedTest
    @ValueSource(strings = {"prod", "prod-blue", "staging"})
    void 개발_테스트가_아닌_프로필에서는_API_문서를_막는다(String profile) {
        // given
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);

        // when & then
        assertThat(SecurityConfig.isApiDocsOpen(environment)).isFalse();
    }

    @Test
    void 프로필_없이_실행하면_기본_프로필_dev로_판단해_API_문서를_연다() {
        // given: application.yaml의 spring.profiles.default가 dev다
        MockEnvironment environment = new MockEnvironment();
        environment.setDefaultProfiles("dev");

        // when & then
        assertThat(SecurityConfig.isApiDocsOpen(environment)).isTrue();
    }

}
