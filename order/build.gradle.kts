plugins {
    id("ok.spring-module-conventions")
}

dependencies {
    implementation(project(":common"))
    testImplementation(project(path = ":common", configuration = "testSupportElements"))
}
