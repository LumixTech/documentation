// bootstrap — çalıştırılabilir uygulama. Tüm adapter'ları bir araya getirir,
// @SpringBootApplication + application.yml burada. `bootJar` bu modülde üretilir.
plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":service-template:application"))
    implementation(project(":service-template:adapter-rest"))
    implementation(project(":service-template:adapter-grpc"))
    implementation(project(":service-template:adapter-kafka"))
    implementation(project(":service-template:adapter-persistence"))

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    // Structured JSON logging (logstash-logback-encoder) — bkz. logback-spring.xml.
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.spring.boot.starter.test)
    // Hexagonal bağımlılık yönü testi tüm modülleri gördüğü için burada.
    testImplementation(libs.archunit.junit5)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

springBoot {
    mainClass.set("com.lumix.template.TemplateServiceApplication")
    // /actuator/info için build bilgisi (build-info.properties üretir).
    buildInfo()
}

// Dockerfile'ın kopyalayacağı sabit isim: app.jar
tasks.bootJar {
    archiveFileName.set("app.jar")
}
