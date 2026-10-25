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

// 앱 클래스가 아직 테스트 소스에 있어 실행 가능한 jar를 만들 main class가 없다. 앱 클래스를 main으로 옮기는 커밋에서 켠다
tasks.bootJar {
    enabled = false
}
