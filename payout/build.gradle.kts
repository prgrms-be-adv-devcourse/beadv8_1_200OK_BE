plugins {
    id("ok.spring-module-conventions")
}

dependencies {
    implementation(project(":common"))
    testImplementation(project(path = ":common", configuration = "testSupportElements"))
    implementation("org.springframework.boot:spring-boot-starter-batch-jdbc") // 배치 실행 기록 DB 저장(BATCH_* 테이블)
    implementation(libs.shedlock.core) // 정산 Job 잠금: 잡기·풀기, 실행 중 자동 연장 (#223)
    implementation(libs.shedlock.jdbc) // 잠금 행을 DB 표(paldogotgan_payout.shedlock)에 적음
}
