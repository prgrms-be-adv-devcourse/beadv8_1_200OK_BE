package com.ok.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

class StandaloneApplicationTest {

    @Test
    void 프로파일을_지정하지_않으면_dev와_모듈_프로파일을_켠다() {
        // given
        Environment environment = new MockEnvironment();

        // when
        String[] sut = StandaloneApplication.profiles("delivery", environment);

        // then
        assertThat(sut).containsExactly("dev", "delivery");
    }

    @Test
    void 환경에서_프로파일을_지정하면_모듈_프로파일만_켠다() {
        // given
        Environment environment = new MockEnvironment().withProperty("spring.profiles.active", "prod");

        // when
        String[] sut = StandaloneApplication.profiles("delivery", environment);

        // then
        assertThat(sut).containsExactly("delivery");
    }

    @Test
    void 실행_인자로_프로파일을_지정하면_모듈_프로파일만_켠다() {
        // given
        Environment environment = StandaloneApplication.environmentOf("--spring.profiles.active=prod");

        // when
        String[] sut = StandaloneApplication.profiles("delivery", environment);

        // then
        assertThat(sut).containsExactly("delivery");
    }
}
