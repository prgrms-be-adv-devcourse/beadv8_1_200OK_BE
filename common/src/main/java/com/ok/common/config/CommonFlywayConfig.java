package com.ok.common.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 기본 스키마(common 소유: event_publication)의 Flyway. 모듈 인스턴스보다 먼저 실행된다({@link ModuleFlyway} 참고).
 * 모듈 단독 앱에서는 만들지 않는다. 모듈 인스턴스가 common 마이그레이션까지 적용하기 때문이다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ok.flyway", name = "standalone", havingValue = "false", matchIfMissing = true)
public class CommonFlywayConfig {

    @Bean
    public Flyway commonFlyway(DataSource dataSource, Environment environment) {
        return ModuleFlyway.createCommon(dataSource, environment);
    }

    @Bean
    public FlywayMigrationInitializer commonFlywayInitializer(@Qualifier("commonFlyway") Flyway commonFlyway) {
        return new FlywayMigrationInitializer(commonFlyway);
    }
}
