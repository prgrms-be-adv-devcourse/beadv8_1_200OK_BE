plugins {
    `kotlin-dsl`
}

dependencies {
    // 컨벤션 플러그인 안에서 id("org.springframework.boot") 등을 쓰려면 플러그인 구현체가 필요하다
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.dependency.management.plugin)
}
