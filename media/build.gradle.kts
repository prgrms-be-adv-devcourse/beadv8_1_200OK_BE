plugins {
    id("ok.spring-module-conventions")
}

dependencies {
    implementation(project(":common"))
    testImplementation(project(path = ":common", configuration = "testSupportElements"))
    implementation(libs.thumbnailator) // 이미지 썸네일·리사이즈
}
