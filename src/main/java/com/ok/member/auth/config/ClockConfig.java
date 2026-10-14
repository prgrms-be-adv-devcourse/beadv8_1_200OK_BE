package com.ok.member.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 현재 시각을 제공하는 {@link Clock} 빈.
 *
 * <p>코드에서 {@code LocalDateTime.now()}를 직접 부르지 않고 Clock을 주입받아 {@code LocalDateTime.now(clock)}으로 쓴다.
 * <ul>
 *   <li>테스트에서 {@code Clock.fixed(...)}로 시각을 고정할 수 있어, 만료 시각을 정확한 값으로 검증할 수 있다.</li>
 *   <li>만료 일시 계산(발급)과 만료 판단({@code RefreshToken.isExpired(now)})이 같은 시각 기준을 쓴다.</li>
 * </ul>
 *
 * <p>시간대는 JVM 기본값을 따른다. 엔티티의 생성·수정 일시(JPA Auditing)도 JVM 기본 시간대의
 * LocalDateTime으로 저장되므로, 같은 기준을 맞추기 위해서다. 서버는 JVM 시간대를 Asia/Seoul로 고정해 실행한다.
 *
 * <p>지금은 auth만 사용해서 member 모듈 안에 둔다. member 모듈만 띄우는 모듈 테스트에서도 빈이 로드되게 하기 위해서다.
 * 다른 모듈도 Clock이 필요해지면 common으로 옮긴다. (같은 타입의 빈이 두 개 생기지 않도록 이 클래스는 삭제)
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
