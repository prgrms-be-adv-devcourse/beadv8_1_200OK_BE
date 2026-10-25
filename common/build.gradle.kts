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
    api("org.springframework.modulith:spring-modulith-starter-core")
    api("software.amazon.awssdk:s3")
    api(libs.owasp.html.sanitizer)

    // BaseEntity 등 공용 @MappedSuperclass의 Q클래스 생성 (annotation processor)
    annotationProcessor(variantOf(libs.querydsl.apt) { classifier("jpa") })
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
}
