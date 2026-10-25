package com.ok.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 앱 전체가 함께 쓰는 현재 시각 기준({@link Clock}) 빈을 등록한다.
 * 코드에서는 {@code LocalDateTime.now()}를 직접 부르지 않고, 이 빈을 주입받아 {@code LocalDateTime.now(clock)}으로 쓴다.
 *
 * <p>Clock을 쓰는 이유
 * <ul>
 *   <li>테스트에서 {@code Clock.fixed(...)}로 시각을 고정할 수 있다. {@code now()}는 부를 때마다 값이 바뀌어
 *       "새벽 2시에 실행되면?" 같은 경우를 테스트할 수 없다.</li>
 *   <li>"지금"을 정하는 곳이 이 빈 하나로 모인다. 모듈마다 시각 기준이 어긋날 일이 없다.</li>
 * </ul>
 *
 * <p>시간대는 JVM 기본값을 따른다({@link Clock#systemDefaultZone()}).
 * <ul>
 *   <li>엔티티의 생성·수정 일시(JPA Auditing, {@code createdAt})도 JVM 기본 시간대로 저장된다.
 *       Clock도 같은 기준을 써야 직접 넣은 시각과 자동으로 기록된 시각이 서로 맞는다.</li>
 *   <li>JVM 기본 시간대는 실행 옵션 {@code -Duser.timezone=Asia/Seoul}로 고정한다(서버는 Dockerfile, CI 테스트는 build.gradle.kts).
 *       그래서 결과적으로 이 Clock은 Asia/Seoul 기준이 된다.</li>
 *   <li>{@code APP_TIME_ZONE}을 직접 읽지 않는 이유: JVM 시간대와 따로 놀면 Auditing 시각과 Clock 시각이 어긋날 수 있어서다.</li>
 * </ul>
 *
 * <p>원래 member 모듈(auth)에만 있던 빈을 여러 모듈이 쓰게 되어 전역 설정으로 옮겼다(#93).
 * 같은 타입({@link Clock})의 빈이 두 개 생기면 어느 것을 주입할지 몰라 앱이 시작되지 않으므로, 다른 곳에 Clock 빈을 새로 만들지 않는다.
 *
 * <p>[주의] 모듈 테스트용 Clock 설정이 따로 있다: {@code com.ok.testsupport.TestClockConfiguration}
 * <ul>
 *   <li>모듈 테스트({@code @ApplicationModuleTest})는 테스트하는 모듈만 띄우고 다른 모듈(이 config 모듈 포함)의 빈은 걸러 낸다.
 *       모듈이 혼자 동작하는지 확인하는 테스트라서, 모듈 밖에서 오는 Clock은 테스트 쪽(testsupport)에서 채운다.</li>
 *   <li>그래서 실제 앱과 통합 테스트는 이 클래스를, 모듈 테스트는 {@code TestClockConfiguration}을 쓴다.</li>
 *   <li>여기서 Clock을 만드는 방식(시간대 기준 등)을 바꾸면 {@code TestClockConfiguration}도 함께 바꾼다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
