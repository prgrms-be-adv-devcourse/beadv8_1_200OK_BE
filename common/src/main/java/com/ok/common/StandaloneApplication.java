package com.ok.common;

import java.util.Map;

import org.springframework.boot.SpringApplication;

/**
 * 모듈 앱 클래스의 {@code main}이 공통으로 쓰는 실행 도우미.
 *
 * <p>모듈을 단독으로 띄울 때 필요한 기본값(접속 기본 DB를 모듈 스키마로, 포트, 설정 프로파일)을 정한다.
 * 기본값은 낮은 우선순위라 환경 변수와 실행 인자가 이긴다. 프로파일은 {@code standalone}(common의
 * application-standalone.yaml)과 모듈 이름(모듈의 application-<모듈>.yaml)을 켠다.
 */
public final class StandaloneApplication {

    private StandaloneApplication() {
    }

    public static void run(Class<?> application, String module, String schema, int port, String... args) {
        SpringApplication springApplication = new SpringApplication(application);
        springApplication.setAdditionalProfiles("standalone", module);
        springApplication.setDefaultProperties(Map.of(
                "ok.module", module,
                "ok.schema", schema,
                "ok.port", port));
        springApplication.run(args);
    }
}
