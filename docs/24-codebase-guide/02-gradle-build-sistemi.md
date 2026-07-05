---
title: "2 · Gradle Build Sistemi — sıfırdan"
description: "Build nedir, Gradle nasıl çalışır, bu repodaki settings/build/catalog dosyaları ne yapar — hiç Gradle görmemiş biri için."
sidebar_position: 2
---

# Gradle Build Sistemi — Sıfırdan

## Bu sayfa ne anlatıyor?

"Build" kelimesini hiç duymamış biri, sayfanın sonunda `backend/` altındaki tüm Gradle
dosyalarının ne işe yaradığını, `./gradlew check` yazınca sırayla ne olduğunu ve yeni
bağımlılığı **nereye** ekleyeceğini bilecek.

## 1. Build nedir? (sıfırdan)

Yazdığın `.java` dosyaları bilgisayarın çalıştırabileceği bir şey değildir. **Build**,
kaynak kodu çalışır ürüne çeviren adımlar zinciridir:

```
.proto → Java üret   →  derle (.java → .class)  →  testleri çalıştır
→  format/stil denetle  →  jar paketle (app.jar)  →  (CI'da) Docker imajı
```

Bu zinciri elle yürütmek yerine bir **build aracına** tarif ederiz. Bizim aracımız
**Gradle**, tarif dili **Kotlin DSL** (`.gradle.kts` dosyaları) —
neden bu seçildi: [ADR-0006](../adr/0006-gradle-kotlin-dsl-build-tool.md).

**Günlük hayat analojisi:** Gradle bir mutfak şefi gibidir. `settings.gradle.kts`
menüdür (hangi yemekler var), `build.gradle.kts` ortak mutfak kurallarıdır (hijyen,
sunum standardı), her modülün kendi `build.gradle.kts`'i o yemeğin özel tarifidir.
Sen sadece "check" dersin; şef sırayı, paralelliği, neyin taze olduğunu kendisi bilir
(değişmeyen task'ları atlar — `UP-TO-DATE`).

## 2. Wrapper: neden `gradle` değil `./gradlew`?

Repoda `gradlew` (Linux/macOS) ve `gradlew.bat` (Windows) betikleri var. Bunlar
**Gradle Wrapper**'dır: `gradle/wrapper/gradle-wrapper.properties` içinde yazan
**sabit Gradle sürümünü** indirir ve onunla çalışır. Böylece:

- Makinene Gradle kurman gerekmez.
- Herkes (ve CI) **aynı** Gradle sürümünü kullanır — "bende çalışıyor" biter.

Kural: her zaman `./gradlew ...` (Windows: `.\gradlew.bat ...`), asla global `gradle`.

## 3. `settings.gradle.kts` — build'in içindekiler sayfası

```kotlin title="backend/settings.gradle.kts (öz)"
rootProject.name = "lumix-backend"

plugins {
    // JDK 25 lokalde yoksa Gradle'ın Foojay üzerinden indirmesini sağlar.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    // Modüller YALNIZCA burada tanımlı repolardan bağımlılık çözebilir.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

include(
    ":service-template:domain",
    ":service-template:application",
    ":service-template:adapter-rest",
    // ... 7 modülün tamamı
)
```

Üç görevi var:

1. **Modül listesi** (`include`): Gradle yalnızca burada sayılan klasörleri "proje"
   olarak görür. Yeni servis eklerken buraya satır eklemeyi unutursan modülün derlenmez.
2. **Repo kilidi**: `FAIL_ON_PROJECT_REPOS` — bir modül kendi kafasına göre başka bir
   Maven reposu eklerse build kırılır. Bağımlılıklar tek kapıdan (Maven Central) gelir.
3. **Toolchain indirici**: geliştiricide JDK 25 yoksa Gradle kendisi indirir
   (bkz. §5) — kurulum derdi yok.

## 4. `gradle/libs.versions.toml` — merkezi bağımlılık kataloğu

**Problem:** 10 servis, her birinde 20 bağımlılık. Sürümler her modülde ayrı yazılırsa
biri 1.68, öteki 1.80 kullanır; kimse fark etmez, üretimde patlar.

**Çözüm:** tüm bağımlılık adları ve sürümleri **tek dosyada** tanımlanır; modüller
sürümsüz **alias** kullanır:

```toml title="backend/gradle/libs.versions.toml (kesit)"
[versions]
springBoot = "4.1.0"
grpc = "1.80.0"        # codegen araçları — Boot BOM'un runtime sürümüyle AYNI tutulur

[libraries]
spring-kafka = { module = "org.springframework.kafka:spring-kafka" }   # sürüm BOM'dan
grpc-stub    = { module = "io.grpc:grpc-stub" }

[plugins]
spring-boot  = { id = "org.springframework.boot", version.ref = "springBoot" }
```

```kotlin title="herhangi bir modülün build.gradle.kts'inde kullanım"
dependencies {
    implementation(libs.spring.kafka)   // ← tire (-) noktaya (.) dönüşür, sürüm YAZILMAZ
}
```

Kural netleşsin: **sürüm değişikliği yalnızca bu dosyada yapılır.** Bir modülde
`"io.grpc:grpc-stub:1.79.0"` gibi elle sürüm görürsen bu bir koku'dur.

### BOM nedir, katalogla ilişkisi ne?

**BOM (Bill of Materials)** = birbiriyle uyumlu sürümlerin hazır listesi. Spring Boot
BOM'u yüzlerce kütüphaneyi (Jackson, Hibernate, Kafka client...) uyumlu sürümlerde
pinler. Kök build her modüle bu BOM'u uygular; bu yüzden katalogda çoğu kütüphane
**sürümsüzdür** — sürümü BOM verir. Katalogda sürümü elle pinlenenler yalnızca BOM'un
yönetmediği veya bilinçli sabitlenen şeylerdir (codegen araçları, ArchUnit, araç sürümleri).

## 5. Kök `build.gradle.kts` — tek yerden ortak convention

`subprojects {}` bloğu, yazdığı her şeyi **tüm modüllere** uygular. Yeni modül
açtığında bunların hepsi bedavaya gelir:

```kotlin title="backend/build.gradle.kts (öz, yorumlu)"
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "checkstyle")            // statik analiz
    apply(plugin = "com.diffplug.spotless") // otomatik format

    configure<JavaPluginExtension> {
        toolchain {
            // Build HER makinede Temurin JDK 25 ile yapılır. Makinede yoksa
            // Gradle indirir (settings'teki Foojay). IDE'deki JDK ne olursa olsun.
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.ADOPTIUM)
        }
    }

    dependencies {
        // Spring Boot BOM: sürüm hizalama (bkz. §4)
        add("implementation", platform(catalog.spring.boot.dependencies))
        // Her modüle ortak test yığını: JUnit 5 + AssertJ
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testImplementation", "org.assertj:assertj-core")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters") // Spring constructor binding için
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // Docker isteyen testler @Tag("integration") ile işaretli;
            // varsayılan check bunları ATLAR. Çalıştırmak: ./gradlew check -Pintegration
            if (!project.hasProperty("integration")) excludeTags("integration")
        }
    }
}
```

Kökte ayrıca: OWASP `dependency-check` (CVE taraması, manuel), SonarQube hazırlığı ve
`apply(from = "gradle/schema-registry.gradle.kts")` ile Apicurio görevleri.

## 6. Görev (task) sözlüğün — günlük komutlar

| Komut | Ne yapar |
|---|---|
| `./gradlew check` | Derle + testler + Spotless doğrula + Checkstyle. **Push'tan önce bunun yeşil olması zorunlu** (pre-push hook'u da bunu koşturur) |
| `./gradlew check -Pintegration` | Üstüne Testcontainers testlerini de koşar (Docker gerekir) — context gerçekten ayağa kalkıyor mu? |
| `./gradlew spotlessApply` | Format ihlallerini **otomatik düzeltir** — format hatası görünce elle uğraşma, bunu çalıştır |
| `./gradlew :service-template:bootstrap:bootRun` | Servisi lokalde çalıştırır (Postgres/Kafka ister) |
| `./gradlew :service-template:bootstrap:bootJar` | Çalıştırılabilir `app.jar` üretir (Docker imajının girdisi) |
| `./gradlew schemaValidate` / `schemaRegister` | Proto şemalarını Apicurio'ya karşı doğrular / kaydeder |
| `./gradlew dependencyCheckAggregate` | OWASP CVE taraması (ağ ister, yavaş — CI'da manuel job) |
| `./gradlew :service-template:adapter-grpc:dependencies` | Bağımlılık ağacını döker — "bu sürüm nereden geldi?" sorusunun cevabı |

`:a:b:task` sözdizimi "yalnızca şu modülün şu task'ı" demektir; modül belirtmezsen
tüm modüllerde koşar.

## 7. `gradle.properties` — davranış ayarları

```properties
org.gradle.parallel=true      # modülleri paralel derle (multi-module'de büyük hız)
org.gradle.caching=true       # task çıktıları cache'lenir; değişmeyen atlanır
org.gradle.configuration-cache=false  # protobuf plugin'i henüz CC-uyumlu değil
org.gradle.java.installations.auto-download=true  # JDK 25 yoksa indir
```

## 8. Dikkat edilecek tuzaklar

- **Sürümü modüle yazma** — kataloğa yaz (§4). PR'da elle sürüm görürsen sorgula.
- **Spring Boot 4 modülerleşmesi** — starter'sız (bare) kütüphane eklediğinde Boot
  otokonfig modülünü de eklemen gerekir (`spring-boot-flyway`, `spring-boot-kafka`...).
  Belirti sinsi olabilir (Flyway sessizce hiç koşmaz). Ayrıntılı tablo:
  [Sprint 0 §4.6](../sprint-implementations/sprint-0-hazirlik-ve-toolchain.md).
- **`build/` klasörünü elle kurcalama** — hepsi üretilir; garip bir durumda
  `./gradlew clean build`.
- **Üretilen kod** (`build/generated/`) Spotless/Checkstyle dışıdır — bizim kodumuz
  değil; ona kural uygulamak anlamsız gürültü üretir.
- **`-Pintegration` unutma** — `check` yeşil diye context'in ayağa kalktığını
  varsayma; DI/otokonfig hatalarını yalnızca entegrasyon smoke testi yakalar.

## 9. Sonraki adım

[Service Template Turu](03-service-template-turu.md) — bu build sisteminin derlediği
asıl kodu, modül modül ve dosya dosya geziyoruz.
