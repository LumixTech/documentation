// adapter-persistence — outbound JPA adapter + Flyway migration'ları.
// application/port/out repository interface'lerini implement eder.
dependencies {
    api(project(":service-template:application"))

    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.flyway.core)
    // Spring Boot 4.x: Flyway otokonfigürasyonu ayrı `spring-boot-flyway` modülünde —
    // bu olmadan flyway-core classpath'te dursa da migration'lar HİÇ çalışmaz
    // (Hibernate `validate` "missing table" ile patlar). Sürüm BOM'dan.
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
