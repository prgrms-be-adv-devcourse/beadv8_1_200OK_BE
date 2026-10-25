package com.ok.common.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.core.env.Environment;

/**
 * 모듈 스키마마다 Flyway 인스턴스(와 이력 테이블)를 하나씩 만드는 도우미.
 *
 * <p>Boot의 Flyway 자동 설정은 Flyway 빈이 하나라는 전제라서, 스키마마다 빈을 직접 만든다. 직접 만든 빈에는
 * {@code spring.flyway.*} 속성이 자동으로 적용되지 않으므로 필요한 값({@code out-of-order}, {@code validate-on-migrate})을
 * 여기서 읽어 넣는다. 개발 환경(dev)의 {@code out-of-order=true}가 모든 인스턴스에 그대로 적용된다.
 *
 * <p>모듈 설정 클래스가 자기 스키마 이름과 모듈 이름만 넘기고, monolith와 모듈 단독 앱이 같은 클래스를 쓴다.
 * 이 클래스는 빈이 아니라 정적 도우미다. 모듈 테스트(@ApplicationModuleTest)는 모듈 밖(common) 클래스의 빈을 걸러 내기 때문이다.
 *
 * <p>실행 방식은 두 가지다. 둘은 {@link #STANDALONE_PROPERTY} 하나로 갈린다.
 * <ul>
 *   <li>monolith(기본): 접속 기본 DB는 기본 스키마다. common 인스턴스가 기본 스키마에 common 마이그레이션을 적용하고,
 *       모듈 인스턴스는 자기 스키마에 자기 마이그레이션만 적용한다.</li>
 *   <li>모듈 단독(standalone): 접속 기본 DB가 모듈 자기 스키마다. common 인스턴스를 만들지 않고, 모듈 인스턴스가
 *       common 마이그레이션까지 함께 적용한다(이력 테이블이 겹치지 않게 인스턴스 하나로 합친다).</li>
 * </ul>
 */
public final class ModuleFlyway {

    /** true면 모듈 단독 앱(또는 모듈 테스트)이다. 모듈 앱 {@code main}과 모듈 테스트 설정이 지정한다. */
    public static final String STANDALONE_PROPERTY = "ok.flyway.standalone";

    static final String COMMON_LOCATION = "classpath:db/migration/common";

    private ModuleFlyway() {
    }

    public static boolean isStandalone(Environment environment) {
        return environment.getProperty(STANDALONE_PROPERTY, Boolean.class, false);
    }

    /**
     * 모듈 스키마용 Flyway.
     *
     * <p>{@code createSchemas=false}: 스키마가 없으면 새로 만들지 않고 실패한다. 이전하지 않은 옛 구조 DB에서
     * 빈 모듈 테이블이 새로 만들어지는 사고를 막는다. 스키마는 DB 준비 스크립트가 만든다.
     */
    public static Flyway create(DataSource dataSource, Environment environment, String schema, String module) {
        String[] locations = isStandalone(environment)
                ? new String[] {"classpath:db/migration/" + module, COMMON_LOCATION}
                : new String[] {"classpath:db/migration/" + module};
        return configure(dataSource, environment)
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(false)
                .locations(locations)
                .load();
    }

    /**
     * 기본 스키마용 Flyway(common 마이그레이션). monolith에서만 만든다.
     *
     * <p>{@code ignoreMigrationPatterns}를 비운다. Flyway 기본값({@code *:future})은 "로컬에 없고 가장 최신 버전보다 새로운"
     * 적용 이력을 무시하는데, 이전하지 않은 옛 구조 DB에는 모듈 마이그레이션 행이 그런 상태로 남아 있다.
     * 무시하면 이 검증이 통과해 이전 안 된 DB에서 앱이 뜨는 사고를 못 막으므로, 모르는 이력이 있으면 실패하게 한다.
     */
    public static Flyway createCommon(DataSource dataSource, Environment environment) {
        return configure(dataSource, environment)
                .createSchemas(false)
                .ignoreMigrationPatterns(new String[0])
                .locations(COMMON_LOCATION)
                .load();
    }

    /**
     * Flyway를 기동 시점에 실행하는 initializer. common initializer가 있으면 그것이 먼저 만들어져(실행되어) 끝난 뒤에 실행된다.
     *
     * <p>옛 구조 DB에서는 common 이력에 모듈 마이그레이션 행이 남아 있어 common 인스턴스의 validate가 실패하고 앱이 멈춘다.
     * 모듈 인스턴스보다 common이 먼저 실행되어야 그 실패가 빈 모듈 테이블 생성보다 앞선다.
     */
    public static FlywayMigrationInitializer initializer(Flyway flyway,
                                                         ObjectProvider<FlywayMigrationInitializer> commonInitializer) {
        commonInitializer.getIfAvailable();
        return new FlywayMigrationInitializer(flyway);
    }

    private static FluentConfiguration configure(DataSource dataSource, Environment environment) {
        return Flyway.configure()
                .dataSource(dataSource)
                .outOfOrder(environment.getProperty("spring.flyway.out-of-order", Boolean.class, false))
                .validateOnMigrate(environment.getProperty("spring.flyway.validate-on-migrate", Boolean.class, true));
    }
}
