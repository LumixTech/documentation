// adapter-kafka — inbound consumer + outbound publisher adapter.
dependencies {
    api(project(":service-template:application"))

    implementation(libs.spring.kafka)
    // Spring Boot 4.x: Kafka otokonfigürasyonu ayrı `spring-boot-kafka` modülünde —
    // bu olmadan KafkaTemplate bean'i üretilmez (context NoSuchBeanDefinition ile çöker).
    implementation("org.springframework.boot:spring-boot-kafka")
    // @ConfigurationProperties (KafkaTopicsProperties) için spring-boot çekirdeği (sürüm BOM'dan).
    implementation("org.springframework.boot:spring-boot")
    // Spring Boot 4.x'te slf4j-api artık spring-kafka üzerinden transitive gelmiyor;
    // bu modül Logger/LoggerFactory'yi doğrudan kullandığı için açıkça bildiriyoruz (sürüm BOM'dan).
    implementation("org.slf4j:slf4j-api")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
}
