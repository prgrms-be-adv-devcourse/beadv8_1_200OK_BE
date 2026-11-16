plugins {
    id("ok.spring-module-conventions")
}

dependencies {
    implementation(project(":common"))
    testImplementation(project(path = ":common", configuration = "testSupportElements"))
    implementation(libs.shedlock.core) // 환불 다시 진행 알람 잠금: 서버가 여러 대여도 한 번에 한 서버만 돈다 (#576)
    implementation(libs.shedlock.jdbc) // 잠금 행을 환불 DB 표(shedlock)에 적음
}
