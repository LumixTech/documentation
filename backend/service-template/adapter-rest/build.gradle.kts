// adapter-rest — inbound HTTP adapter. application/port/in'i çağırır.
dependencies {
    api(project(":service-template:application"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)

    testImplementation(libs.spring.boot.starter.test)
}
