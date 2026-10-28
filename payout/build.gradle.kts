plugins {
    id("ok.spring-module-conventions")
}

dependencies {
    implementation(project(":common"))
    testImplementation(project(path = ":common", configuration = "testSupportElements"))
    implementation("org.springframework.boot:spring-boot-starter-batch-jdbc") // 배치 실행 기록 DB 저장(BATCH_* 테이블)
}
