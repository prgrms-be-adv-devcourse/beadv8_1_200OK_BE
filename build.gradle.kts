plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com"
version = "0.0.1-SNAPSHOT"
description = "paldo-gotgan"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.1.1"
extra["awsSdkVersion"] = "2.55.12"

val querydslVersion = "7.7"
val springdocVersion = "3.1.1"
val archunitVersion = "1.5.1"

dependencies {
    // ---------- Web / API ----------
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion") // Swagger UI

    // ---------- Spring Modulith (도메인 모듈, 이벤트) ----------
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa") // 이벤트 발행 기록(event_publication)
    runtimeOnly("org.springframework.modulith:spring-modulith-runtime")

    // ---------- Batch ----------
    implementation("org.springframework.boot:spring-boot-starter-batch")

    // ---------- Security ----------
    implementation("org.springframework.boot:spring-boot-starter-security")

    // ---------- Database (JPA, Querydsl, MySQL) ----------
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("io.github.openfeign.querydsl:querydsl-jpa:$querydslVersion")
    runtimeOnly("com.mysql:mysql-connector-j")

    // Querydsl Q클래스 생성 (annotation processor)
    annotationProcessor("io.github.openfeign.querydsl:querydsl-apt:$querydslVersion:jpa")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // ---------- DB 마이그레이션 (Flyway) ----------
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")

    // ---------- AWS ----------
    implementation("software.amazon.awssdk:s3")

    // ---------- Image ----------
    implementation("net.coobird:thumbnailator:0.4.21")

    // ---------- 개발 편의 ----------
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose") // 앱 실행 시 docker compose 자동 기동 (DOCKER_COMPOSE_ENABLED)

    // ---------- Test: Spring Boot 테스트 스타터 ----------
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-batch-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")

    // ---------- Test: Testcontainers ----------
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")

    // ---------- Test: 구조 검증 / 기타 ----------
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
        mavenBom("software.amazon.awssdk:bom:${property("awsSdkVersion")}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 테스트는 application-test.yml 을 사용 (dev 기본 프로파일 대신)
    systemProperty("spring.profiles.active", "test")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}
