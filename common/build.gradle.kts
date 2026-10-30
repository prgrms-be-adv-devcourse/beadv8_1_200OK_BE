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
    // 모듈 간 이벤트를 메시지 브로커로 옮길 기반 (#318). 접속 설정은 DB처럼 monolith와 모듈 yaml이 각자 갖는다
    // 모든 모듈이 같은 브로커를 쓰는 공통 인프라라 common의 api로 연다(#288). 연결은 처음 쓸 때 맺어 쓰지 않는 모듈의 비용은 거의 없다
    api("org.springframework.boot:spring-boot-starter-kafka")

    runtimeOnly("com.mysql:mysql-connector-j")

    annotationProcessor(variantOf(libs.querydsl.apt) { classifier("jpa") })
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
}
