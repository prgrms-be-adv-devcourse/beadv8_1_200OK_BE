plugins {
    id("ok.java-conventions")
    `java-library`
}

// 모든 도메인 모듈이 쓰는 공용 코드. 이 모듈의 공개 타입에 노출되는 의존은 api로 열어 둔다
dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api(libs.querydsl.jpa)
    api(libs.springdoc.webmvc.ui)
    api("org.springframework.modulith:spring-modulith-starter-core")
    api("software.amazon.awssdk:s3")
    api(libs.owasp.html.sanitizer)

    // BaseEntity 등 공용 @MappedSuperclass의 Q클래스 생성 (annotation processor)
    annotationProcessor(variantOf(libs.querydsl.apt) { classifier("jpa") })
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
}

// 다른 서브프로젝트의 테스트가 가져다 쓰는 테스트 지원 코드(MySQL 컨테이너, Clock, application-test.yml)
// java-test-fixtures(jar)를 쓰지 않는 이유: Spring Modulith는 jar 안의 com.ok.testsupport를 도메인 모듈로 인식해
// 구조 테스트와 모듈 테스트(@ApplicationModuleTest)가 깨진다. test 소스셋의 classes 디렉터리로 노출하면
// 지금처럼 테스트 클래스로 인식되어 모듈 분석에서 제외된다
val testSupport by configurations.creating {
    isCanBeResolved = false
    isCanBeConsumed = true
    extendsFrom(configurations.testImplementation.get())
}

artifacts {
    add(testSupport.name, sourceSets.test.get().output.classesDirs.singleFile) {
        builtBy(tasks.compileTestJava)
    }
    add(testSupport.name, sourceSets.test.get().output.resourcesDir!!) {
        builtBy(tasks.processTestResources)
    }
}
