import org.gradle.api.artifacts.VersionCatalogsExtension

// 도메인 모듈끼리는 project(...) 의존을 걸지 않는다(선언은 PR diff에서 보이므로 리뷰에서 확인한다)
plugins {
    id("ok.java-conventions")
    `java-library`
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    // Querydsl Q클래스 생성. 어노테이션 프로세서는 의존으로 전달되지 않아 모듈마다 선언한다
    annotationProcessor(variantOf(libs.findLibrary("querydsl-apt").get()) { classifier("jpa") })
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
}

tasks.bootRun {
    environment("OK_ROOT_DIR", rootProject.projectDir.absolutePath)
}

// 통합 테스트의 @ActiveProfiles("test")가 spring.profiles.active를 덮어쓰므로 include로 더한다
tasks.withType<Test> {
    systemProperty("spring.profiles.include", project.name)
    systemProperty("ok.schema", "paldogotgan_${project.name}")
    // 슬라이스 테스트와 전체 컨텍스트 테스트가 한 이력을 쓰게 한다(ModuleFlyway.SINGLE_HISTORY_PROPERTY)
    systemProperty("ok.flyway.single-history", "true")
}
