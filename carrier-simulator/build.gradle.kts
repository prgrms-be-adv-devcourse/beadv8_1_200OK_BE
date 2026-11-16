// Delivery Tracker를 대신하는 시험용 서버. monolith에 넣지 않고 단독으로만 실행한다 (#559)
plugins {
    id("ok.java-conventions")
    alias(libs.plugins.spring.boot)
}

// 이 모듈은 DB·Redis·Kafka·Security를 쓰지 않는다. 컨벤션 플러그인이 모든 모듈에 붙이는 테스트 스타터가 올라오면
// 전체 컨텍스트 테스트에서 DataSource 자동 설정이 실패하므로 뺀다
configurations.matching { it.name.startsWith("test") }.configureEach {
    listOf("data-jpa", "data-redis", "flyway", "security", "batch").forEach {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-$it-test")
    }
    exclude(group = "org.springframework.boot", module = "spring-boot-testcontainers")
    exclude(group = "org.springframework.modulith", module = "spring-modulith-starter-test")
    exclude(group = "org.testcontainers")
    exclude(group = "com.redis", module = "testcontainers-redis")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-graphql")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
}
