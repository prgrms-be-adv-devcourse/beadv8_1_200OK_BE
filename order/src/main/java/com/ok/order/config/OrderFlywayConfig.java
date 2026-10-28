package com.ok.order.config;

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
import com.ok.order.OrderSchema;

/**
 * order 스키마의 Flyway. 스키마마다 Flyway와 이력 테이블을 따로 둔다({@link ModuleFlyway} 참고).
 */
@Configuration(proxyBeanMethods = false)
public class OrderFlywayConfig {

    @Bean
    public Flyway orderFlyway(DataSource dataSource, Environment environment) {
        return ModuleFlyway.create(dataSource, environment, OrderSchema.NAME, "order");
    }

    /**
     * 모듈 단독 앱에서만, 같은 스키마에 common 마이그레이션을 별도 이력 테이블로 적용한다({@link ModuleFlyway#createStandaloneCommon}).
     */
    @Bean
    @ConditionalOnExpression("${ok.flyway.standalone:false} and !${ok.flyway.single-history:false}")
    public Flyway orderCommonFlyway(DataSource dataSource, Environment environment) {
        return ModuleFlyway.createStandaloneCommon(dataSource, environment);
    }

    @Bean
    @ConditionalOnExpression("${ok.flyway.standalone:false} and !${ok.flyway.single-history:false}")
    public FlywayMigrationInitializer orderCommonFlywayInitializer(
            @Qualifier("orderCommonFlyway") Flyway orderCommonFlyway,
            @Qualifier("orderFlywayInitializer") FlywayMigrationInitializer orderFlywayInitializer) {
        // orderFlywayInitializer를 파라미터로 받아 모듈 마이그레이션이 먼저 끝난 뒤 실행되게 한다
        return new FlywayMigrationInitializer(orderCommonFlyway);
    }

    @Bean
    public FlywayMigrationInitializer orderFlywayInitializer(
            @Qualifier("orderFlyway") Flyway orderFlyway,
            @Qualifier("commonFlywayInitializer") ObjectProvider<FlywayMigrationInitializer> commonInitializer) {
        return ModuleFlyway.initializer(orderFlyway, commonInitializer);
    }
}
