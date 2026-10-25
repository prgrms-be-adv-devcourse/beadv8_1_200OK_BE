package com.ok.media.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.ok.common.config.ModuleFlyway;
import com.ok.media.MediaSchema;

/**
 * media 스키마의 Flyway. 스키마마다 Flyway와 이력 테이블을 따로 둔다({@link ModuleFlyway} 참고).
 */
@Configuration(proxyBeanMethods = false)
public class MediaFlywayConfig {

    @Bean
    public Flyway mediaFlyway(DataSource dataSource, Environment environment) {
        return ModuleFlyway.create(dataSource, environment, MediaSchema.NAME, "media");
    }

    @Bean
    public FlywayMigrationInitializer mediaFlywayInitializer(
            @Qualifier("mediaFlyway") Flyway mediaFlyway,
            @Qualifier("commonFlywayInitializer") ObjectProvider<FlywayMigrationInitializer> commonInitializer) {
        return ModuleFlyway.initializer(mediaFlyway, commonInitializer);
    }
}
