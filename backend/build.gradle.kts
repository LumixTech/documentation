// =====================================================================
// Lumix backend — kök build. Tüm ortak convention'lar burada (subprojects).
// service-template ve ondan türeyen her servis bu convention'ları miras alır:
//   - Java 25 toolchain (ADR-002)
//   - Spring Boot BOM (sürüm hizalama)
//   - Spotless (format) + Checkstyle (statik analiz) → `check`e bağlı
//   - dependency-check (OWASP) + SonarQube → ayrı task, ağ gerektirir
// =====================================================================
import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec

plugins {
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.dependency.check)
    alias(libs.plugins.sonarqube)
}

group = "com.lumix"
version = "0.1.0-SNAPSHOT"

// Gradle 9'da tip-güvenli `libs` accessor'ı `subprojects {}` bloğu içinde çözülemiyor
// (accessor kök projeye bağlı). Kök scriptte bir kez yakalayıp alt projelerde kullanıyoruz.
val catalog = libs

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "checkstyle")
    apply(plugin = "com.diffplug.spotless")

    group = rootProject.group
    version = rootProject.version

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.ADOPTIUM)
        }
    }

    // Spring Boot BOM — tüm modüllerde sürüm hizalama (starter'lar versiyonsuz kullanılır).
    // testImplementation, implementation'ı extend ettiği için BOM test classpath'ine de yansır.
    dependencies {
        add("implementation", platform(catalog.spring.boot.dependencies))
        add("annotationProcessor", platform(catalog.spring.boot.dependencies))
        // Ortak test yığını (sürümler BOM'dan) — her modül JUnit 5 + AssertJ kullanır.
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testImplementation", "org.assertj:assertj-core")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    configure<CheckstyleExtension> {
        toolVersion = catalog.versions.checkstyle.get()
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        isIgnoreFailures = false
        maxWarnings = 0
    }

    // Üretilen protobuf/gRPC kaynakları (build/generated) Checkstyle dışında tutulur —
    // checkstyle.xml başlığındaki niyetin uygulaması. Bu srcDir kök olduğundan pattern
    // yerine gerçek dosya yolunu denetliyoruz.
    tasks.withType<Checkstyle>().configureEach {
        exclude { it.file.absolutePath.replace('\\', '/').contains("/build/generated/") }
    }

    configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            // Üretilen protobuf/gRPC kaynaklarını formatlamadan hariç tut.
            targetExclude("**/build/generated/**")
            palantirJavaFormat()
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
            importOrder("java", "javax", "jakarta", "org", "com", "net", "io", "")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // -parameters: Spring constructor binding + @ConfigurationProperties için gerekli.
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // Testcontainers (Docker gerektiren) testler `@Tag("integration")` ile işaretli;
            // varsayılan `check` bunları atlar. Çalıştırmak için: ./gradlew check -Pintegration
            if (!project.hasProperty("integration")) {
                excludeTags("integration")
            }
        }
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}

// --- OWASP dependency-check: `./gradlew dependencyCheckAggregate` (NVD verisi indirir, ağ ister) ---
dependencyCheck {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "SARIF")
    // NVD API anahtarı CI'da env'den: NVD_API_KEY (rate-limit için önerilir).
    nvd { apiKey = System.getenv("NVD_API_KEY") ?: "" }
    // Test-only bağımlılıkları tarama dışı bırak (opsiyonel; gürültüyü azaltır).
    skipConfigurations = listOf("checkstyle", "spotless")
}

// --- SonarQube hazırlığı: `./gradlew sonar` (SONAR_HOST_URL + SONAR_TOKEN env ister) ---
sonar {
    properties {
        property("sonar.projectKey", "lumix-backend")
        property("sonar.projectName", "Lumix Backend")
        property("sonar.sourceEncoding", "UTF-8")
        // JaCoCo XML raporu eklenince coverage buradan okunur (şablon: hazırlık).
    }
}
