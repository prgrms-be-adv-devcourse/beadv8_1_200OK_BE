package com.ok.common;

import org.springframework.boot.SpringApplication;
import org.springframework.core.env.Environment;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 모듈 앱 클래스의 {@code main}이 공통으로 쓰는 실행 도우미.
 *
 * <p>모듈 이름 프로파일을 켜서 모듈의 application-<모듈>.yaml을 읽게 한다.
 * 프로파일을 지정하지 않았으면 monolith의 기본 프로파일처럼 dev도 켠다.
 */
public final class StandaloneApplication {

    private static final String ACTIVE_PROFILES_PROPERTY = "spring.profiles.active";
    private static final String DEFAULT_PROFILE = "dev";

    private StandaloneApplication() {
    }

    public static void run(Class<?> application, String module, String... args) {
        SpringApplication springApplication = new SpringApplication(application);
        springApplication.setAdditionalProfiles(profiles(module, environmentOf(args)));
        springApplication.run(args);
    }

    static Environment environmentOf(String... args) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        return environment;
    }

    static String[] profiles(String module, Environment environment) {
        if (environment.containsProperty(ACTIVE_PROFILES_PROPERTY)) {
            return new String[] {module};
        }
        return new String[] {DEFAULT_PROFILE, module};
    }
}
