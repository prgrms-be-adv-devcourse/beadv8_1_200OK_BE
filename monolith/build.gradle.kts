plugins {
    id("ok.java-conventions")
    alias(libs.plugins.spring.boot)
}

group = "com"
version = "0.0.1-SNAPSHOT"
description = "paldo-gotgan"

dependencies {
    implementation(project(":common"))
    implementation(project(":media"))
    implementation(project(":refund"))
    testImplementation(project(path = ":common", configuration = "testSupportElements"))

    // ---------- Web / API ----------
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator") // 컨테이너 헬스체크(/actuator/health)
    implementation(libs.springdoc.webmvc.ui) // Swagger UI

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
    implementation(libs.querydsl.jpa)
    runtimeOnly("com.mysql:mysql-connector-j")

    // Querydsl Q클래스 생성 (annotation processor)
    annotationProcessor(variantOf(libs.querydsl.apt) { classifier("jpa") })
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // ---------- DB 마이그레이션 (Flyway) ----------
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")

    // ---------- AWS ----------
    implementation("software.amazon.awssdk:s3")

    // ---------- HTML ----------
    implementation(libs.owasp.html.sanitizer) // 상품 상세설명 HTML 정제 (XSS 방지)

    // ---------- 개발 편의 ----------
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose") // 앱 실행 시 docker compose 자동 기동 (DOCKER_COMPOSE_ENABLED)
}

// .env 와 docker-compose.yml 은 저장소 루트에 있고 둘 다 실행 디렉터리 기준으로 찾는다
// 서브프로젝트로 옮겨도 bootRun이 지금처럼 동작하도록 실행 디렉터리를 루트로 둔다
tasks.bootRun {
    workingDir = rootProject.projectDir
}
