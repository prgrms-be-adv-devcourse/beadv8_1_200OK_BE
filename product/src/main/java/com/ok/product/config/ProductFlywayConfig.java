package com.ok.product.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.ok.common.config.ModuleFlyway;
import com.ok.product.ProductSchema;

/**
 * product 스키마의 Flyway. 스키마마다 Flyway와 이력 테이블을 따로 둔다({@link ModuleFlyway} 참고).
 */
@Configuration(proxyBeanMethods = false)
public class ProductFlywayConfig {

    @Bean
    public Flyway productFlyway(DataSource dataSource, Environment environment) {
        return ModuleFlyway.create(dataSource, environment, ProductSchema.NAME, "product");
    }

    @Bean
    public FlywayMigrationInitializer productFlywayInitializer(
            @Qualifier("productFlyway") Flyway productFlyway,
            @Qualifier("commonFlywayInitializer") ObjectProvider<FlywayMigrationInitializer> commonInitializer) {
        return ModuleFlyway.initializer(productFlyway, commonInitializer);
    }
}
