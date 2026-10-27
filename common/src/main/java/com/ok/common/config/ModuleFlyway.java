package com.ok.common.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.core.env.Environment;

/**
 * 모듈 스키마마다 Flyway 인스턴스와 이력 테이블을 하나씩 만드는 정적 도우미.
 *
 * <p>모듈 테스트({@code @ApplicationModuleTest})가 모듈 밖(common) 클래스의 빈을 걸러 내므로 빈이 아니라 정적 도우미로 둔다.
 * 실행 방식은 {@link #STANDALONE_PROPERTY} 하나로 갈린다.
 */
public final class ModuleFlyway {

    /** true면 모듈 단독 앱(또는 모듈 테스트)이다. */
    public static final String STANDALONE_PROPERTY = "ok.flyway.standalone";

    public static final String STANDALONE_COMMON_TABLE = "flyway_schema_history_common";

    /**
     * true면 모듈 Flyway 하나가 common 마이그레이션까지 한 이력에 적용한다. 슬라이스 테스트({@code @DataJpaTest})가
     * Boot의 Flyway 하나만 써서 생기는 이력 불일치를 맞추는 모듈 테스트 전용 값이다.
     */
    public static final String SINGLE_HISTORY_PROPERTY = "ok.flyway.single-history";

    static final String COMMON_LOCATION = "classpath:db/migration/common";

    private ModuleFlyway() {
    }

    public static boolean isStandalone(Environment environment) {
        return environment.getProperty(STANDALONE_PROPERTY, Boolean.class, false);
    }

    /**
     * 모듈 스키마용 Flyway. 스키마가 없으면 새로 만들지 않고 실패한다({@code createSchemas=false}).
     * 이전하지 않은 옛 구조 DB에 빈 모듈 테이블이 생기는 사고를 막기 위해서다.
     */
    public static Flyway create(DataSource dataSource, Environment environment, String schema, String module) {
        return configure(dataSource, environment)
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(false)
                .locations(environment.getProperty(SINGLE_HISTORY_PROPERTY, Boolean.class, false)
                        ? new String[] {"classpath:db/migration/" + module, COMMON_LOCATION}
                        : new String[] {"classpath:db/migration/" + module})
                .load();
    }

    /**
     * 모듈 단독 앱에서 모듈 스키마에 common 마이그레이션을 별도 이력 테이블로 적용한다.
     * 모듈 Flyway가 먼저 돌아 스키마가 비어 있지 않으므로 버전 0으로 기준선을 잡고 시작한다.
     */
    public static Flyway createStandaloneCommon(DataSource dataSource, Environment environment) {
        return configure(dataSource, environment)
                .table(STANDALONE_COMMON_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .createSchemas(false)
                .locations(COMMON_LOCATION)
                .load();
    }

    /**
     * 기본 스키마용 Flyway(common 마이그레이션). monolith에서만 만든다.
     * 이전하지 않은 옛 구조 DB의 모듈 마이그레이션 이력이 무시되지 않고 실패하도록 {@code ignoreMigrationPatterns}를 비운다.
     */
    public static Flyway createCommon(DataSource dataSource, Environment environment) {
        return configure(dataSource, environment)
                .createSchemas(false)
                .ignoreMigrationPatterns(new String[0])
                .locations(COMMON_LOCATION)
                .load();
    }

    /**
     * Flyway를 기동 시점에 실행한다. 먼저 실행할 initializer(common)가 있으면 그것이 끝난 뒤에 실행해,
     * 옛 구조 DB에서의 실패가 빈 모듈 테이블 생성보다 앞서게 한다.
     */
    @SafeVarargs
    public static FlywayMigrationInitializer initializer(Flyway flyway,
                                                         ObjectProvider<FlywayMigrationInitializer>... prerequisites) {
        for (ObjectProvider<FlywayMigrationInitializer> prerequisite : prerequisites) {
            prerequisite.getIfAvailable();
        }
        return new FlywayMigrationInitializer(flyway);
    }

    private static FluentConfiguration configure(DataSource dataSource, Environment environment) {
        return Flyway.configure()
                .dataSource(dataSource)
                .outOfOrder(environment.getProperty("spring.flyway.out-of-order", Boolean.class, false))
                .validateOnMigrate(environment.getProperty("spring.flyway.validate-on-migrate", Boolean.class, true));
    }
}
