package com.ok.testsupport;

import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * 모듈 테스트({@code @ApplicationModuleTest})에서 쓰는 Clock 빈을 등록한다.
 *
 * <p>원칙: 모듈 테스트는 모듈 밖에서 오는 것을 테스트가 직접 채워 준다.
 * <ul>
 *   <li>모듈 테스트의 목적은 "이 모듈이 다른 모듈 없이 혼자 동작하는가"를 확인하는 것이다.
 *       나중에 모듈을 서비스로 분리(MSA)할 때 혼자 동작하는 것이 보장되어 있어야 하기 때문이다.</li>
 *   <li>그래서 다른 모듈({@code com.ok.common.config})의 설정을 끌어오지 않고, 필요한 부품을 테스트 쪽에서 채운다.
 *       DB를 {@link TestcontainersConfiguration}이 채우는 것과 같은 방식이다.</li>
 * </ul>
 *
 * <p>[주의] 운영 설정 {@code com.ok.common.config.ClockConfig}와 같은 방식으로 Clock을 만든다.
 * <ul>
 *   <li>운영 {@code ClockConfig}에서 Clock을 만드는 방식(시간대 기준 등)을 바꾸면 이 클래스도 함께 바꾼다.
 *       둘이 다르면 모듈 테스트와 실제 앱의 시각 기준이 어긋난다.</li>
 * </ul>
 *
 * <p>운영 {@code ClockConfig}를 그대로 {@code @Import}하지 않는 이유
 * <ul>
 *   <li>모듈 테스트는 테스트하는 모듈만 띄우고 다른 모듈의 빈은 걸러 낸다. {@code com.ok.common.config}도 하나의 모듈이라 걸러진다.</li>
 *   <li>{@code @Import(ClockConfig.class)}로 불러와도 {@code ClockConfig} 객체가 걸러져서,
 *       {@code clock()} 정의만 남고 실행할 객체가 없어 컨텍스트가 뜨지 않는다
 *       ({@code No bean named 'com.ok.common.config.ClockConfig' available}).</li>
 *   <li>이 패키지(testsupport)는 테스트 폴더에만 있어 Modulith 모듈이 아니므로 걸러지지 않는다.</li>
 * </ul>
 *
 * <p>클래스에 {@code @TestConfiguration}(또는 {@code @Configuration})을 붙이지 않는 이유
 * <ul>
 *   <li>통합 테스트의 부모 {@code AbstractIntegrationTest}에 있는 {@code @ComponentScan("com.ok")}이 테스트 폴더(testsupport)까지 스캔한다.</li>
 *   <li>어노테이션을 붙이면 통합 테스트가 이 클래스를 자동으로 주워 가서, 운영 {@code ClockConfig}와 이름이 같은
 *       {@code clock} 빈이 두 개가 되어 컨텍스트가 뜨지 않는다.</li>
 *   <li>{@code @Import}는 어노테이션 없는 클래스도 받아서 그 안의 {@code @Bean} 메서드를 등록한다.
 *       그래서 모듈 테스트에서 {@code @Import}로 부른 곳에만 Clock이 올라온다.</li>
 * </ul>
 *
 * <p>정리: 실제 앱과 통합 테스트는 운영 {@code ClockConfig}를, 모듈 테스트는 이 클래스를 쓴다. 어느 경우든 Clock 빈은 하나다.
 */
public class TestClockConfiguration {

    // 운영 ClockConfig#clock()과 같은 방식: JVM 기본 시간대를 따른다 (테스트 JVM은 build.gradle.kts에서 Asia/Seoul로 고정)
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
