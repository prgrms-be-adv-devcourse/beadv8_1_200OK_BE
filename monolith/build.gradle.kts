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
    implementation(project(":member"))
    implementation(project(":refund"))
    implementation(project(":wallet"))
    implementation(project(":delivery"))
    implementation(project(":product"))
    implementation(project(":order"))
    implementation(project(":payout"))
    testImplementation(project(path = ":common", configuration = "testSupportElements"))
    testImplementation(project(path = ":delivery", configuration = "testSupportElements")) // 택배사 Fake(FakeCarrierClient)

    // 스타터(web, security, JPA, Flyway, MySQL, Modulith, Swagger, AWS 등)는 common이 api로 열어 둔다
    runtimeOnly("org.springframework.modulith:spring-modulith-runtime")

    // Querydsl Q클래스 생성 (annotation processor)
    annotationProcessor(variantOf(libs.querydsl.apt) { classifier("jpa") })
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // ---------- 개발 편의 ----------
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose") // 앱 실행 시 docker compose 자동 기동 (DOCKER_COMPOSE_ENABLED)
}

// .env 와 docker-compose.yml 은 저장소 루트에 있고 둘 다 실행 디렉터리 기준으로 찾는다
// 서브프로젝트로 옮겨도 bootRun이 지금처럼 동작하도록 실행 디렉터리를 루트로 둔다
tasks.bootRun {
    workingDir = rootProject.projectDir
}
