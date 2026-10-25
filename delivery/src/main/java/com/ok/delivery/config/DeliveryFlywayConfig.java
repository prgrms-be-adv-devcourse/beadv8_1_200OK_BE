package com.ok.delivery.config;

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
import com.ok.delivery.DeliverySchema;

/**
 * delivery 스키마의 Flyway. 스키마마다 Flyway와 이력 테이블을 따로 둔다({@link ModuleFlyway} 참고).
 */
@Configuration(proxyBeanMethods = false)
public class DeliveryFlywayConfig {

    @Bean
    public Flyway deliveryFlyway(DataSource dataSource, Environment environment) {
        return ModuleFlyway.create(dataSource, environment, DeliverySchema.NAME, "delivery");
    }

    /**
     * 모듈 단독 앱에서만, 같은 스키마에 common 마이그레이션(event_publication)을 별도 이력 테이블로 적용한다.
     * 모듈 이력에 common 행이 섞이면 같은 DB를 monolith로 띄울 때 검증이 실패한다.
     * 모듈 Flyway가 먼저 실행되어 스키마에 테이블이 있으므로 이 인스턴스는 기준선을 잡고 시작한다({@link ModuleFlyway#createStandaloneCommon}).
     */
    @Bean
    @ConditionalOnExpression("${ok.flyway.standalone:false} and !${ok.flyway.single-history:false}")
    public Flyway deliveryCommonFlyway(DataSource dataSource, Environment environment) {
        return ModuleFlyway.createStandaloneCommon(dataSource, environment);
    }

    @Bean
    @ConditionalOnExpression("${ok.flyway.standalone:false} and !${ok.flyway.single-history:false}")
    public FlywayMigrationInitializer deliveryCommonFlywayInitializer(
            @Qualifier("deliveryCommonFlyway") Flyway deliveryCommonFlyway,
            @Qualifier("deliveryFlywayInitializer") FlywayMigrationInitializer deliveryFlywayInitializer) {
        // deliveryFlywayInitializer를 파라미터로 받아 모듈 마이그레이션이 먼저 끝난 뒤 실행되게 한다
        return new FlywayMigrationInitializer(deliveryCommonFlyway);
    }

    @Bean
    public FlywayMigrationInitializer deliveryFlywayInitializer(
            @Qualifier("deliveryFlyway") Flyway deliveryFlyway,
            @Qualifier("commonFlywayInitializer") ObjectProvider<FlywayMigrationInitializer> commonInitializer) {
        return ModuleFlyway.initializer(deliveryFlyway, commonInitializer);
    }
}
