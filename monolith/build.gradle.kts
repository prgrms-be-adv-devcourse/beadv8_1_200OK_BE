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

    runtimeOnly("org.springframework.modulith:spring-modulith-runtime")

    // Querydsl Q클래스 생성 (annotation processor)
    annotationProcessor(variantOf(libs.querydsl.apt) { classifier("jpa") })
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // ---------- 개발 편의 ----------
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose") // 앱 실행 시 docker compose 자동 기동 (DOCKER_COMPOSE_ENABLED)
}

// 떠 있는 Redis·Kafka가 필요한 테스트(@Tag("infraTest"), #530)는 기본 test(CI 포함)에서 뺀다
tasks.test {
    useJUnitPlatform {
        excludeTags("infraTest")
    }
}

val infraTest by tasks.registering(Test::class) {
    description = "compose로 띄운 실제 Redis·Kafka에 앱 설정으로 붙는지 확인한다"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("infraTest")
    }
    // 인프라 상태는 Gradle이 모르는 입력이라 매번 다시 돌린다
    outputs.upToDateWhen { false }
    // 공통 설정의 jacocoTestReport가 일반 test(Testcontainers)를 먼저 돌려서 뗀다
    setFinalizedBy(emptyList<Any>())
}

// .env와 docker-compose.yml을 저장소 루트에서 찾으므로 bootRun 실행 디렉터리를 루트로 둔다
tasks.bootRun {
    workingDir = rootProject.projectDir
}
