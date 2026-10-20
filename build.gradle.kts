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
    implementation("org.springframework.boot:spring-boot-starter-actuator") // 컨테이너 헬스체크(/actuator/health)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion") // Swagger UI

    // ---------- Spring Modulith (도메인 모듈, 이벤트) ----------
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa") // 이벤트 발행 기록(event_publication)
    runtimeOnly("org.springframework.modulith:spring-modulith-runtime")

    // ---------- Batch ----------
    implementation("org.springframework.boot:spring-boot-starter-batch-jdbc") // 배치 실행 기록 DB 저장(BATCH_* 테이블)

    // ---------- Security ----------
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")

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

    // ---------- HTML ----------
    implementation("com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20260924.2") // 상품 상세설명 HTML 정제 (XSS 방지)

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
    // 테스트 JVM의 시간대를 팀 기준(Asia/Seoul)으로 고정 (#93)
    // - LocalDateTime.now(), Clock.systemDefaultZone(), JPA Auditing(createdAt)은 JVM 기본 시간대를 따른다
    // - 로컬(Windows)은 OS가 KST라 문제가 없지만, CI(GitHub Actions)는 UTC라서 같은 테스트가 다른 날짜로 계산될 수 있다
    //   예) 정산 실행일이 하루 앞당겨져 로컬은 통과하고 CI는 실패하는 상황
    // - 서버(Docker)도 Dockerfile에서 같은 값으로 고정한다. 둘 중 하나를 바꾸면 다른 쪽도 함께 바꾼다
    jvmArgs("-Duser.timezone=Asia/Seoul")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}
