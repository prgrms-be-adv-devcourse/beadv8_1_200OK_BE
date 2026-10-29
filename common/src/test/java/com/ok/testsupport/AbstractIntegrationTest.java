package com.ok.testsupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@Tag("integrationTest")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ComponentScan(basePackages = "com.ok")
// 서브프로젝트마다 클래스패스에 있는 모듈이 달라, 모듈 전용 Fake(택배사 등)는 각 모듈의 테스트 지원 클래스가 @Component로 스스로 등록한다
// Kafka를 쓰는 통합 테스트가 @Import 없이 이 클래스만 상속하면 되도록 Kafka 컨테이너도 함께 켠다 (#318)
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
public class AbstractIntegrationTest {
}
