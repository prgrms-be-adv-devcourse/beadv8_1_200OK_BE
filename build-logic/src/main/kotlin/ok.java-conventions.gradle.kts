import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    java
    jacoco
    id("io.spring.dependency-management")
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.findVersion("spring-boot").get()}")
        mavenBom("org.springframework.modulith:spring-modulith-bom:${libs.findVersion("spring-modulith").get()}")
        mavenBom("software.amazon.awssdk:bom:${libs.findVersion("aws-sdk").get()}")
    }
}

dependencies {
    // ---------- Lombok ----------
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

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
    testImplementation(libs.findLibrary("archunit-junit5").get())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    // 테스트 컨테이너가 모듈 스키마를 만드는 초기화 폴더. 운영·로컬 MySQL과 같은 스크립트를 쓴다(TestcontainersConfiguration)
    systemProperty("ok.initdb-dir", rootProject.layout.projectDirectory.dir("deploy/initdb").asFile.absolutePath)
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// 이 프로젝트의 테스트 지원 코드(com.ok.testsupport)와 테스트 설정 파일을 다른 서브프로젝트의 테스트가 가져다 쓰는 통로
// java-test-fixtures(jar)를 쓰지 않는 이유: Spring Modulith는 jar 안의 com.ok.testsupport를 도메인 모듈로 인식해
// 구조 테스트와 모듈 테스트(@ApplicationModuleTest)가 깨진다. test 소스셋의 classes 디렉터리로 노출하면
// 지금처럼 테스트 클래스로 인식되어 모듈 분석에서 제외된다
// 사용: testImplementation(project(path = ":common", configuration = "testSupportElements"))
val testSupportElements = configurations.consumable("testSupportElements") {
    extendsFrom(configurations.testImplementation.get())
}

artifacts {
    add(testSupportElements.name, sourceSets.test.get().output.classesDirs.singleFile) {
        builtBy(tasks.compileTestJava)
    }
    add(testSupportElements.name, sourceSets.test.get().output.resourcesDir!!) {
        builtBy(tasks.processTestResources)
    }
}
