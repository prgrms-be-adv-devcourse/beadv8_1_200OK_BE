plugins {
    id("ok.java-conventions")
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-actuator") // 컨테이너 헬스체크(/actuator/health)
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-flyway")
    api("org.flywaydb:flyway-mysql")
    api(libs.querydsl.jpa)
    api(libs.springdoc.webmvc.ui)
    api("org.springframework.modulith:spring-modulith-starter-core")
    api("org.springframework.modulith:spring-modulith-starter-jpa") // 이벤트 발행 기록(event_publication)
    api("software.amazon.awssdk:s3")
    api(libs.owasp.html.sanitizer)

    runtimeOnly("com.mysql:mysql-connector-j")

    annotationProcessor(variantOf(libs.querydsl.apt) { classifier("jpa") })
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
}
