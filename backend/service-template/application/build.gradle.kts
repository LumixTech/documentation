// application — use case orchestration (inbound/outbound port'lar + service'ler).
// İzin verilen framework: yalnızca Spring stereotype/tx (pragmatik hexagonal, bkz. 03-hexagonal §4.5).
// Web/JPA/Kafka/gRPC bağımlılığı buraya GİRMEZ — onlar adapter modüllerde.
dependencies {
    api(project(":service-template:domain"))

    implementation(libs.spring.context) // @Service, @Component
    implementation(libs.spring.tx) // @Transactional

    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
}
