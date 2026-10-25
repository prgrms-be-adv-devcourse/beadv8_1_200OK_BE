package com.ok.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 MySQL 컨테이너를 JVM 전체에서 하나만 띄워 모든 테스트 컨텍스트가 공유하게 한다 (#151).
 * <p>컨테이너를 빈으로 등록하지 않고 접속 정보({@link JdbcConnectionDetails})만 빈으로 등록하는 이유
 * <ul>
 *   <li>컨테이너를 {@code @Bean}으로 등록하면 컨텍스트마다 컨테이너가 새로 뜬다. 컨텍스트 캐시는 JVM이 끝날 때까지 컨텍스트를 닫지 않아서 컨테이너가 계속 쌓인다</li>
 *   <li>같은 컨테이너 객체를 빈으로 돌려줘도 Spring Boot가 컨텍스트를 닫을 때 컨테이너 빈을 {@code close()}해서, 한 컨텍스트가 닫히면 다른 컨텍스트의 DB까지 멈춘다</li>
 *   <li>컨테이너는 JVM이 끝나면 Testcontainers의 Ryuk가 정리한다</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // 모듈이 소유한 스키마(MySQL 데이터베이스). 컨테이너를 띄울 때 모두 만든다 (각 모듈의 XxxSchema.NAME과 같은 값)
    private static final List<String> MODULE_SCHEMAS = Stream.of(
                    "member", "wallet", "order", "product", "delivery", "payout", "media", "refund")
            .map(module -> "paldogotgan_" + module)
            .toList();

    private static MySQLContainer mysql;

    /**
     * 접속 기본 DB는 {@code ok.schema} 속성이 정한다. 모듈 서브프로젝트의 테스트는 자기 모듈 스키마(컨벤션 플러그인이 지정)를,
     * monolith 테스트는 속성이 없어 컨테이너의 기본 DB를 쓴다. 모듈 테이블을 한정자 없이 쓰는 모듈 테스트가 그대로 동작한다.
     */
    @Bean
    JdbcConnectionDetails mysqlConnectionDetails(@Value("${MYSQL_VERSION:9.4}") String mysqlVersion,
                                                 @Value("${ok.schema:}") String schema) {
        MySQLContainer container = startMysql(mysqlVersion);
        String jdbcUrl = schema.isBlank()
                ? container.getJdbcUrl()
                : container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/" + schema);
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return container.getUsername();
            }

            @Override
            public String getPassword() {
                return container.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return jdbcUrl;
            }

            @Override
            public String getDriverClassName() {
                return container.getDriverClassName();
            }
        };
    }

    private static synchronized MySQLContainer startMysql(String mysqlVersion) {
        if (mysql == null) {
            MySQLContainer container = new MySQLContainer(DockerImageName.parse("mysql:" + mysqlVersion))
                    .withEnv("TZ", "Asia/Seoul");
            container.start();
            createModuleSchemas(container);
            mysql = container;
        }
        return mysql;
    }

    // 컨테이너의 테스트 계정은 데이터베이스를 만들 권한이 없어 root로 만들고 테스트 계정에 권한을 준다
    private static void createModuleSchemas(MySQLContainer container) {
        String sql = MODULE_SCHEMAS.stream()
                .map(schema -> "CREATE DATABASE IF NOT EXISTS `%1$s` DEFAULT CHARACTER SET utf8mb4; GRANT ALL ON `%1$s`.* TO '%2$s'@'%%';"
                        .formatted(schema, container.getUsername()))
                .collect(Collectors.joining(" "));
        try {
            ExecResult result = container.execInContainer("mysql", "-uroot", "-p" + container.getPassword(), "-e", sql);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("모듈 스키마를 만들지 못했다: " + result.getStderr());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

}
