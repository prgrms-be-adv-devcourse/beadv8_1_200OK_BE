package com.ok.testsupport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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

    private static MySQLContainer mysql;

    @Bean
    JdbcConnectionDetails mysqlConnectionDetails(@Value("${MYSQL_VERSION:9.4}") String mysqlVersion) {
        MySQLContainer container = startMysql(mysqlVersion);
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
                return container.getJdbcUrl();
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
            mysql = container;
        }
        return mysql;
    }

}
