import org.gradle.api.artifacts.VersionCatalogsExtension

// 도메인 모듈(서브프로젝트)용 컨벤션: 플러그인, Q클래스 생성, 모듈 단독 기동·테스트 설정만 준다
// 의존성은 모듈의 build.gradle.kts에 직접 적는다. 스타터는 common이 api로 열어 두므로 모듈은 project(":common")만 가져오면 된다
// 도메인 모듈끼리는 project(...) 의존을 걸지 않는다(선언하지 않은 모듈은 컴파일할 수 없고, 선언은 PR diff에서 보이므로 리뷰에서 확인한다)
plugins {
    id("ok.java-conventions")
    `java-library`
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    // Querydsl Q클래스 생성 (annotation processor). 프로세서는 의존으로 전달되지 않아 모듈마다 필요하다
    annotationProcessor(variantOf(libs.findLibrary("querydsl-apt").get()) { classifier("jpa") })
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
}

// 단독 실행은 모듈 폴더의 .env(모듈 DB 계정)와 루트 .env(공유 시크릿)를 함께 읽는다(application-standalone.yaml)
tasks.bootRun {
    environment("OK_ROOT_DIR", rootProject.projectDir.absolutePath)
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
