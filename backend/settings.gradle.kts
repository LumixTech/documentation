// =====================================================================
// Lumix backend mono-repo — Gradle kök ayarları.
// Her microservice, service-template'ten türetilir (bkz. service-template/README.md).
// Yeni servis eklerken: klasörü kopyala, aşağıya include(...) satırlarını ekle.
// =====================================================================
rootProject.name = "lumix-backend"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // JDK 25 lokalde yoksa Gradle'ın Foojay üzerinden toolchain indirmesini sağlar.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    // Modüller yalnızca burada tanımlı repolardan çözer (izin: repo drift'i engellenir).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    // gradle/libs.versions.toml otomatik olarak `libs` catalog'u olarak yüklenir.
}

// --- service-template (yeni servislerin kopyalanacağı iskelet) ---
include(
    ":service-template:domain",
    ":service-template:application",
    ":service-template:adapter-rest",
    ":service-template:adapter-grpc",
    ":service-template:adapter-kafka",
    ":service-template:adapter-persistence",
    ":service-template:bootstrap",
)
