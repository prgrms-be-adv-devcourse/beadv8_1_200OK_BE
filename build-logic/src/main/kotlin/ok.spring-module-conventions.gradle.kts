import org.gradle.api.artifacts.VersionCatalogsExtension

// 도메인 모듈(서브프로젝트)용 컨벤션: 공통 의존(common)과 모듈이 단독으로 기동하는 데 필요한 스타터를 준다
// 도메인 모듈끼리는 project(...) 의존을 걸지 않는다(Gradle이 컴파일 단계에서 모듈 간 의존을 막는다)
plugins {
    id("ok.java-conventions")
    `java-library`
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(project(":common"))
    testImplementation(project(path = ":common", configuration = "testSupportElements"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator") // 컨테이너 헬스체크(/actuator/health)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa") // 이벤트 발행 기록(event_publication)
    implementation(libs.findLibrary("springdoc-webmvc-ui").get())
    implementation(libs.findLibrary("querydsl-jpa").get())
    runtimeOnly("com.mysql:mysql-connector-j")

    // Querydsl Q클래스 생성 (annotation processor)
    annotationProcessor(variantOf(libs.findLibrary("querydsl-apt").get()) { classifier("jpa") })
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
}

// 모듈 테스트는 모듈을 단독으로 띄운 것과 같게 돈다. 접속 기본 DB는 자기 모듈 스키마이고(ok.schema),
// standalone 프로파일(common의 application-standalone.yaml)과 모듈 프로파일(모듈의 application-<모듈>.yaml)을 함께 켠다
// 통합 테스트의 @ActiveProfiles("test")가 spring.profiles.active를 덮어쓰므로 include로 더한다
tasks.withType<Test> {
    systemProperty("spring.profiles.include", "standalone,${project.name}")
    systemProperty("ok.schema", "paldogotgan_${project.name}")
    // 슬라이스 테스트(@DataJpaTest)의 Boot Flyway와 전체 컨텍스트 테스트의 모듈 Flyway가 한 이력을 쓰게 한다(ModuleFlyway.SINGLE_HISTORY_PROPERTY)
    systemProperty("ok.flyway.single-history", "true")
}
