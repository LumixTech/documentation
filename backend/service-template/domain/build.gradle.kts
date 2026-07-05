// domain — hexagonal ÇEKİRDEK. Framework bağımlılığı YOK (sadece JDK).
// Kural (ArchUnit ile denetlenir): domain, application/adapter paketlerini import edemez.
// Buraya asla Spring/JPA/Kafka bağımlılığı ekleme.
dependencies {
    // Kasıtlı olarak boş: saf Java 25.
}
