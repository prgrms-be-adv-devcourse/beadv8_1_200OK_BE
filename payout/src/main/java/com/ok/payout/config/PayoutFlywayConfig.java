package com.ok.payout.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.ok.common.config.ModuleFlyway;
import com.ok.payout.PayoutSchema;

/**
 * payout 스키마의 Flyway. 스키마마다 Flyway와 이력 테이블을 따로 둔다({@link ModuleFlyway} 참고).
 */
@Configuration(proxyBeanMethods = false)
public class PayoutFlywayConfig {

    @Bean
    public Flyway payoutFlyway(DataSource dataSource, Environment environment) {
        return ModuleFlyway.create(dataSource, environment, PayoutSchema.NAME, "payout");
    }

    /**
     * 모듈 단독 앱에서만, 같은 스키마에 common 마이그레이션을 별도 이력 테이블로 적용한다({@link ModuleFlyway#createStandaloneCommon}).
     */
    @Bean
    @ConditionalOnExpression("${ok.flyway.standalone:false} and !${ok.flyway.single-history:false}")
    public Flyway payoutCommonFlyway(DataSource dataSource, Environment environment) {
        return ModuleFlyway.createStandaloneCommon(dataSource, environment);
    }

    @Bean
    @ConditionalOnExpression("${ok.flyway.standalone:false} and !${ok.flyway.single-history:false}")
    public FlywayMigrationInitializer payoutCommonFlywayInitializer(
            @Qualifier("payoutCommonFlyway") Flyway payoutCommonFlyway,
            @Qualifier("payoutFlywayInitializer") FlywayMigrationInitializer payoutFlywayInitializer) {
        // payoutFlywayInitializer를 파라미터로 받아 모듈 마이그레이션이 먼저 끝난 뒤 실행되게 한다
        return new FlywayMigrationInitializer(payoutCommonFlyway);
    }

    @Bean
    public FlywayMigrationInitializer payoutFlywayInitializer(
            @Qualifier("payoutFlyway") Flyway payoutFlyway,
            @Qualifier("commonFlywayInitializer") ObjectProvider<FlywayMigrationInitializer> commonInitializer) {
        return ModuleFlyway.initializer(payoutFlyway, commonInitializer);
    }
}
