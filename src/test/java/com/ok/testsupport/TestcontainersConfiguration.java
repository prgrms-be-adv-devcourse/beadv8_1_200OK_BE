package com.ok.testsupport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer(@Value("${MYSQL_VERSION:9.4}") String mysqlVersion) {
        return new MySQLContainer(DockerImageName.parse("mysql:" + mysqlVersion))
                // 테스트용 MySQL 서버 시간대를 팀 기준(Asia/Seoul)으로 고정 (#93). 설정이 없으면 컨테이너 기본값 UTC
                // - NOW(), CURRENT_TIMESTAMP 기본값(예: media 테이블)이 이 시간대를 따르므로 테스트 JVM(build.gradle.kts)과 같게 맞춘다
                // - @ServiceConnection이 DB 주소를 직접 만들어서 application.yaml의 serverTimezone이 적용되지 않으므로 컨테이너에 직접 지정한다
                // - 로컬·서버용 MySQL은 infra/mysql/compose.yml에서 같은 값으로 고정한다. 둘 중 하나를 바꾸면 다른 쪽도 함께 바꾼다
                .withEnv("TZ", "Asia/Seoul");
    }

}
