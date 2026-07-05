---
title: "Sprint 0 — Hazırlık & Toolchain"
description: "Sprint 0'da kurulan her şeyin implementasyon kaydı: toolchain, git iş akışı, GitLab CI, hexagonal service template, ADR'ler ve Apicurio schema registry — neden ve nasıl, gerçek örneklerle."
sidebar_position: 0
---

# Sprint 0 — Hazırlık & Toolchain (27 May – 9 Haz 2026)

## Bu sayfa ne anlatıyor?

Sprint 0'da **tek satır ürün kodu yazılmadı** — onun yerine, sonraki 15 sprintte
yazılacak her satır kodun üzerinde koşacağı **zemin** kuruldu. Bu sayfa o zeminin
implementasyon kaydıdır: ne yapıldı, **neden** yapıldı, **nasıl** yapıldı ve sen yeni
bir şey eklerken bunları **nasıl kullanacaksın**. Konuyu hiç bilmeyen bir geliştirici
bu sayfayı bitirdiğinde repo'daki her dosyanın ne işe yaradığını bilmeli.

> **Kod nerede?** Mono-repo: `campus/` (`gitlab.hsoylu.dev/lumix/campus`).
> Bu dokümantasyon sitesi (`documentation/`) ayrı bir projedir; mono-repo'ya dahil değildir.

### Sprint 0 teslimat haritası

| İş (ClickUp task) | Repo'daki karşılığı | Durum |
|---|---|---|
| Yerel geliştirme ortamı ve toolchain | `.tool-versions`, `.sdkmanrc`, `.nvmrc`, `.editorconfig` | ✅ |
| Repo stratejisi + branching + commit/PR konvansiyonu | `.githooks/`, `scripts/`, `docs/git-workflow.md`, `.gitmessage`, `CODEOWNERS` | ✅ |
| GitLab CE self-hosted + Container Registry + Runner | `gitlab.hsoylu.dev` (sunucu) + `.gitlab-ci.yml` | ✅ |
| Spring Boot service template (Hexagonal + Gradle multi-module) | `backend/service-template/` + `backend/build.gradle.kts` | ✅ |
| ADR şablonu + ilk 6 ADR | [ADR bölümü](../adr/0001-mono-repo.md) (bu sitede) | ✅ |
| Apicurio Registry kurulumu + Protobuf POC | `infra/apicurio/`, `backend/gradle/schema-registry.gradle.kts`, `backend/buf.yaml` | 🔄 devam ediyor |
| GitLab restore drill testi | (henüz yapılmadı) | ⏳ |

---

## 1. Yerel geliştirme ortamı ve toolchain

### Nedir, hangi problemi çözüyor?

İki kişilik takımda biri Windows, biri Linux kullanıyor. "Bende çalışıyor" problemi
tam olarak buradan doğar: birinin makinesinde Java 21, diğerinde Java 25 varsa aynı kod
farklı davranır. Çözüm: **sürümleri dosyaya yazmak** ve araçların bu dosyayı okumasını
sağlamak. Sürüm artık sözlü anlaşma değil, repo'da versiyonlanan bir sözleşmedir.

### Nasıl yapıldı?

Repo kökünde üç sürüm-pinleme dosyası var; üçü de **aynı sürümleri** söyler:

```text title="campus/.tool-versions  (asdf + mise okur — Windows'ta önerilen)"
java temurin-25.0.1
nodejs 24
```

- `.sdkmanrc` → SDKMAN kullananlar için (`sdk env`), Java 25.0.1-tem
- `.nvmrc` → nvm kullananlar için, Node `24`
- `.tool-versions` → **mise**/asdf için ikisi bir arada. Windows'ta `nvm-windows`
  `.nvmrc`'yi okumaz, SDKMAN Git Bash ister; **mise** native çalıştığı için Windows'ta
  tek komut yeter: `mise install`.

İkinci savunma hattı **Gradle Java toolchain**'dir (bkz. §4.1): geliştiricinin
makinesinde hangi JDK olursa olsun *build* her zaman Temurin JDK 25 ile yapılır;
yoksa Gradle, Foojay üzerinden kendisi indirir. Yani toolchain dosyaları IDE/terminal
konforu içindir, build doğruluğunu Gradle garantiler.

### Yeni bir araç/sürüm eklerken

Sürüm değişikliğinde **dört yeri birlikte** güncelle: `.nvmrc`, `.sdkmanrc`,
`.tool-versions` ve CI imajı (`.gitlab-ci.yml` → `eclipse-temurin:25-jdk`).
Editor davranışı da `.editorconfig` ile sabitlendi (UTF-8, LF, 4 boşluk indent) —
IDE fark etmeksizin aynı format.

---

## 2. Repo stratejisi, branching ve commit konvansiyonu

### Nedir, hangi problemi çözüyor?

Karar ([ADR-0001](../adr/0001-mono-repo.md)): **mono-repo + trunk-based development**. Backend, frontend
ve infra tek repoda (`campus/`); herkes kısa ömürlü branch açar, küçük MR'larla
`main`'e döner. `main` her zaman yeşildir (build geçer, dağıtılabilir).

Bunu sözle değil **otomatik kapılarla** zorluyoruz — kurallar hook ve CI'da yaşar,
insan hafızasına güvenilmez:

| Aşama | Kontrol | Nerede |
|---|---|---|
| commit | Conventional Commits formatı + ClickUp `Refs: CU-...` footer'ı | `.githooks/commit-msg` |
| commit | merge işareti, büyük dosya, sır sızması | `.githooks/pre-commit` |
| push | **build zorunlu** (kırmızıysa push iptal) | `.githooks/pre-push` |
| MR | build + test + commit formatı + proto uyumluluğu | `.gitlab-ci.yml` |
| MR | 1 onay (zorunlu olarak diğer kişiden) + CODEOWNERS | GitLab protected branch |

### Nasıl kullanılır? (günlük akış)

```bash
# 1) ClickUp task'ına bağlı branch aç — adı otomatik <tip>/CU-<id>-<slug> olur
bash scripts/new-branch.sh feature 86abc123 "kullanici giris akisi"

# 2) Kod yaz... sonra build kontrolü
bash scripts/build-check.sh --changed

# 3) Commit — format doğrulanır, ClickUp ref'i branch adından otomatik eklenir
bash scripts/commit.sh feat auth "kullanici giris akisi eklendi"

# 4) Push (pre-push hook build'i zorunlu çalıştırır) → GitLab'da MR aç
git push -u origin HEAD
```

Her `.sh` betiğinin `.ps1` ikizi vardır (Windows PowerShell). Commit mesaj formatı:

```
<tip>(<kapsam>): <konu>          # konu Türkçe, ~72 karakter, sonunda nokta yok
feat(auth): kullanici giris akisi eklendi
fix(payment): iade tutari yanlis hesaplaniyordu
```

### Dikkat: tuzaklar

- **MR'daki TÜM commit'ler** formata uymalı; tek bozuk commit `commit-lint` job'ını
  kırar. Bozuk mesajı `git rebase` ile reword et (asla `main`'de).
- Acil kaçış: mesajı `!` ile başlat (`!acil hotfix`) — hem lokal hook hem CI bypass
  eder ama iz kalır. Gerçekten acilse kullan.
- Ayrıntılı playbook: `campus/docs/git-workflow.md` · inceleme eksenleri:
  `campus/docs/REVIEW_CHECKLIST.md`.

---

## 3. GitLab CE (self-hosted) + Container Registry + CI

### Nedir, neden self-hosted?

Lumix **on-prem** bir üründür; kod ve imajlar müşteri verisi gibi bizim altyapımızda
kalır. `gitlab.hsoylu.dev` üzerinde GitLab CE, yanında Container Registry (Docker
imajları için) ve pipeline'ları koşturan Runner kuruldu.

### Pipeline ne yapıyor? (`campus/.gitlab-ci.yml`)

Dört stage: `build → security → image → deploy`.

1. **`backend:build`** — `./gradlew check --profile`: derleme + test + Spotless +
   Checkstyle. Yalnızca `backend/**` değişince koşar (MR'da); `main`'de her zaman.
   JUnit raporları MR widget'ına düşer; Gradle `--profile` çıktısı task-bazlı timing
   HTML'i olarak artifact'lenir.
2. **`commit-lint`** — MR'daki tüm commit konularını Conventional Commits regex'iyle
   denetler (lokal hook'un sunucu kopyası — lokal hook atlatılabilir, CI atlatılamaz).
3. **`schema:validate`** — `.proto` değiştiyse `buf lint` + `buf breaking` (bkz. §6.4).
4. **`backend:dependency-check`** — OWASP taraması; NVD verisi indirdiği için manuel
   tetiklenir, `allow_failure: true`.
5. **`backend:image`** — Kaniko ile (Docker daemon'sız) distroless imaj build + push:
   `$CI_REGISTRY_IMAGE/service-template:<sha>`.
6. **`deploy:staging`** — placeholder; K8s/Helm gelince (Sprint 14) dolacak.

Her job süresini OpenMetrics formatında `metrics.txt`'e yazar
(`artifacts:reports:metrics`) — GitLab MR widget'ında **trend** olarak görünür;
CI yavaşlaması veriyle takip edilir.

> Repo'da `.github/workflows/ci.yml` de var — bu **GitLab'da çalışmaz**; yalnızca
> GitHub mirror kullanılırsa devreye giren yedek güvenlik ağıdır. Asıl pipeline GitLab'dır.

### ⏳ Açık iş: restore drill

Yedeği alınan ama geri dönüşü hiç denenmemiş sistem, yedeksiz sayılır. GitLab
yedeğinden temiz bir makineye kurtarma tatbikatı ("Gitlab Restore Drill Testi")
Sprint 0'dan devreden açık iştir.

---

## 4. Spring Boot service template — `backend/service-template/`

Sprint 0'ın en büyük parçası. Bundan sonra her mikroservis (identity, organization,
academic...) bu iskeletten **kopyalanarak** doğar: mimari, kalite kapıları, Docker
imajı, konfigürasyon — hepsi hazır gelir; sen yalnızca domain'ini yazarsın.

### 4.1 Gradle multi-module + version catalog

**Neden Gradle (Kotlin DSL)?** [ADR-0006](../adr/0006-gradle-kotlin-dsl-build-tool.md): tip güvenli build script'leri,
güçlü çoklu-modül desteği, protobuf codegen plugin'i. **Neden multi-module?**
Hexagonal mimarinin bağımlılık kuralları modül sınırlarıyla *fiziksel olarak*
zorlanır (aşağıda §4.2).

Yapı üç dosyada döner:

- **`backend/settings.gradle.kts`** — hangi modüller build'e dahil, tek repo listesi
  (`FAIL_ON_PROJECT_REPOS`: modüller kendi repo'sunu ekleyemez, drift engellenir),
  Foojay toolchain resolver (JDK 25 yoksa indirir).
- **`backend/gradle/libs.versions.toml`** — **merkezi bağımlılık kataloğu.** Sürüm
  değişikliği *yalnızca* burada yapılır; modüller `libs.spring.kafka` gibi sürümsüz
  alias kullanır. Spring Boot BOM zaten çoğu sürümü pinlediği için katalogda sadece
  BOM-dışı şeyler (gRPC, protobuf, ArchUnit, araç sürümleri) durur.
- **`backend/build.gradle.kts`** — kök build; `subprojects {}` bloğuyla **tüm
  modüllere ortak convention** basar:

```kotlin title="backend/build.gradle.kts (öz)"
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "checkstyle")
    apply(plugin = "com.diffplug.spotless")

    configure<JavaPluginExtension> {
        toolchain {                                  // build her makinede AYNI JDK ile
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.ADOPTIUM)
        }
    }
    dependencies {
        add("implementation", platform(catalog.spring.boot.dependencies)) // BOM: sürüm hizalama
        add("testImplementation", "org.junit.jupiter:junit-jupiter")      // ortak test yığını
    }
}
```

Yani yeni modül açtığında Java 25, format, statik analiz, BOM ve JUnit **kendiliğinden**
gelir — modülün `build.gradle.kts`'inde yalnızca *o modüle özgü* bağımlılıklar yazılır.

### 4.2 Hexagonal mimari: modüller ve bağımlılık yönü

**Nedir?** ([ADR-0005](../adr/0005-hexagonal-architecture.md), ayrıntı: [Hexagonal Architecture](../02-architecture-patterns/03-hexagonal-architecture.md))
İş mantığını (domain) dış dünyadan (HTTP, gRPC, Kafka, veritabanı) **port** denen
arayüzlerle yalıtan mimari. Dış dünya değişir (REST yerine gRPC, Postgres yerine
başka DB) ama çekirdek değişmez. Bağımlılık oku **her zaman içeri** bakar:

```
adapter-rest ─┐                                  ┌─ adapter-persistence
adapter-grpc ─┼→ application (port'lar) → domain │
adapter-kafka─┘         ↑ implements             └─ adapter-kafka (out)
                    bootstrap (hepsini bir araya getirir, çalıştırır)
```

| Modül | Sorumluluk | İzinli bağımlılık |
|---|---|---|
| `domain` | Aggregate, value object, domain event, iş kuralı | **sadece JDK** (framework YOK) |
| `application` | Use case orkestrasyonu; inbound/outbound **port** arayüzleri | domain + Spring stereotype/tx |
| `adapter-rest` | Inbound HTTP: controller, DTO, RFC 7807 hata | application |
| `adapter-grpc` | Inbound gRPC: `.proto` → codegen → servis | application |
| `adapter-kafka` | Inbound consumer + outbound event publisher | application |
| `adapter-persistence` | Outbound JPA + Flyway migration | application |
| `bootstrap` | `@SpringBootApplication`, `application.yml`, `bootJar` | hepsi |

**Domain — iş kuralları burada yaşar, başka hiçbir yerde değil:**

```java title="domain/.../model/Sample.java (öz)"
public final class Sample {                       // framework annotation'ı YOK
    public static Sample create(SampleId id, String name, Instant createdAt) {
        Sample sample = new Sample(id, name, SampleStatus.DRAFT, createdAt);
        sample.domainEvents.add(new SampleCreatedEvent(id, createdAt));  // event biriktirir
        return sample;
    }
    public void activate() {
        if (status == SampleStatus.ACTIVE) {
            throw new SampleAlreadyActiveException(id);   // invariant: iş kuralı domain'de
        }
        this.status = SampleStatus.ACTIVE;
    }
}
```

**Application — port tanımlar, orkestre eder; iş kuralı içermez:**

```java title="application/.../port/out/SampleRepository.java  (outbound PORT: sadece arayüz)"
public interface SampleRepository {
    Sample save(Sample sample);
    Optional<Sample> findById(SampleId id);
}
```

```java title="application/.../service/CreateSampleService.java (use case)"
@Service
@Transactional                       // transaction sınırı use case'tir
public class CreateSampleService implements CreateSampleUseCase {
    @Override
    public SampleId create(CreateSampleCommand command) {
        Sample sample = Sample.create(SampleId.newId(), command.name(), clock.instant());
        repository.save(sample);                              // port üzerinden — JPA'yı bilmez
        sample.domainEvents().forEach(eventPublisher::publish); // port üzerinden — Kafka'yı bilmez
        sample.clearDomainEvents();
        return sample.id();
    }
}
```

**Adapter — portu implemente eder (out) veya portu çağırır (in):**

```java title="adapter-persistence/.../SampleRepositoryAdapter.java (outbound adapter)"
@Component
public class SampleRepositoryAdapter implements SampleRepository {   // portu implemente eder
    public Sample save(Sample sample) {
        SampleJpaEntity saved = jpaRepository.save(SampleEntityMapper.toEntity(sample));
        return SampleEntityMapper.toDomain(saved);   // JPA entity ↔ domain modeli AYRI sınıflar
    }
}
```

```java title="adapter-rest/.../SampleController.java (inbound adapter)"
@RestController
@RequestMapping("/api/v1/samples")
public class SampleController {
    @PostMapping
    public ResponseEntity<SampleResponse> create(@RequestBody @Valid CreateSampleRequest request) {
        SampleId id = createSampleUseCase.create(new CreateSampleCommand(request.name()));
        return ResponseEntity.created(URI.create("/api/v1/samples/" + id.value())) ...
    }   // controller yalnızca inbound PORT'u (use case) çağırır; repository'ye dokunamaz
}
```

Hata yönetimi: domain exception'ları `ApiExceptionHandler` (`@RestControllerAdvice`)
RFC 7807 `ProblemDetail`'e çevirir — ayrıntı:
[Error Handling](../03-backend/05-error-handling-rfc7807.md).

**Kural nasıl zorlanıyor?** İki katman:

1. **Gradle modül sınırı** — `domain/build.gradle.kts`'te bağımlılık listesi **bilerek
   boş**; domain'e Spring eklemeye kalkarsan derleme anında görürsün.
2. **ArchUnit testi** — paket seviyesinde de denetler; CI'da otomatik koşar:

```java title="bootstrap/.../HexagonalArchitectureTest.java (öz)"
@ArchTest
static final ArchRule domain_application_ve_adapteri_bilmez = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..");
```

### 4.3 Kalite kapıları: Spotless + Checkstyle + test etiketleri

- **Spotless** (Palantir Java Format): format tartışması bitiren otomatik formatter.
  `./gradlew spotlessApply` düzeltir, `check` doğrular. Üretilen protobuf kodu hariç.
- **Checkstyle**: formatla çakışmayan *yapısal* kurallar (yıldız import yasağı, satır
  uzunluğu 120, boş catch yasağı...). `maxWarnings = 0` — uyarı da kabul edilmez.
- **Test etiketleri**: Docker isteyen Testcontainers testleri `@Tag("integration")`
  ile işaretli; `./gradlew check` bunları atlar, `./gradlew check -Pintegration` koşar.
  Böylece Docker'ı olmayan makinede de `check` yeşildir.

### 4.4 `application.yml` — bilinçli varsayılanlar

`bootstrap/src/main/resources/application.yml` şablon geleni herkes miras alır:

- `spring.threads.virtual.enabled: true` — Java 25 **virtual threads**
  ([ayrıntı](../03-backend/02-java-25-virtual-threads.md)); thread-per-request
  modelinde bloklanan I/O ucuzlar.
- `jpa.hibernate.ddl-auto: validate` — şemayı **Flyway** yönetir
  (`db/migration/V1__*.sql`); JPA asla DDL üretmez.
- `jpa.open-in-view: false` — lazy-loading-in-view / N+1 tuzağı kapalı.
- Kafka producer: `acks: all` + `enable.idempotence: true` — kayıpsız üretim;
  consumer: `enable-auto-commit: false` + `read_committed`.
- Actuator: `/actuator/health/liveness|readiness` K8s probe'ları için;
  readiness'a **DB dahil** (DB düşükse pod trafik almaz); `/actuator/prometheus` metrik.
- Sır **yazılmaz**: her şey `${ENV_VAR:default}` kalıbıyla environment'tan gelir.

### 4.5 Dockerfile — distroless, üç aşama

```
1) eclipse-temurin:25-jdk  → ./gradlew bootJar        (derleme)
2) jlink                   → uygulamaya yetecek küçük JRE üret
3) gcr.io/distroless/base  → sadece JRE + app.jar     (shell yok, paket yöneticisi yok)
```

Sonuç: < 200 MB, saldırı yüzeyi minimum (imajda shell bile yok — bu yüzden K8s
probe'ları `httpGet` ile, `curl` ile değil). `MaxRAMPercentage=75` container memory
limitine göre heap ayarlar.

### 4.6 Dikkat: Spring Boot 4 modülerleşme tuzağı

Spring Boot 4'te birçok otokonfigürasyon `spring-boot-autoconfigure`'dan çıkarılıp
**ayrı modüllere** bölündü. Sonuç: bir kütüphaneyi classpath'e koymak **yetmez**,
Boot'un o kütüphane için otokonfig modülünü de eklemen gerekir — yoksa hata bazen
sessizdir (Flyway hiç çalışmaz), bazen açılışta patlar (KafkaTemplate bean'i yok):

| Kütüphane | Yanına eklenecek Boot modülü | Eksikse ne olur |
|---|---|---|
| `flyway-core` | `org.springframework.boot:spring-boot-flyway` | Migration **sessizce hiç koşmaz**; `ddl-auto: validate` "missing table" ile patlar |
| `spring-kafka` | `org.springframework.boot:spring-boot-kafka` | `KafkaTemplate` bean'i üretilmez → context açılışta çöker |
| `spring-boot-resttestclient` (test) | `org.springframework.boot:spring-boot-restclient` | `NoClassDefFoundError: RestTemplateBuilder` |

`spring-boot-starter-*` ile gelenler güvendedir (starter, otokonfig modülünü zaten
taşır); tuzak **bare kütüphane** eklerken. Şablonda bunlar düzeltilmiş ve build
dosyalarında yorumla işaretlenmiştir. Yeni bir teknoloji eklerken önce
`spring-boot-<teknoloji>` diye bir modül var mı diye bak; doğrulamanın kesin yolu
entegrasyon smoke testidir: `./gradlew check -Pintegration` (Docker gerekir).

### 4.7 Yeni servis nasıl türetilir? (≈10 dk)

Özet (tam adımlar: `backend/service-template/README.md`):

1. `cp -r service-template academic-service`
2. Paket adını değiştir: `com.lumix.template` → `com.lumix.academic` (sed + git mv)
3. `settings.gradle.kts`'e 7 modülün `include(...)` satırlarını ekle;
   modül içi `project(":service-template:...")` yollarını güncelle
4. `Sample` dilimini kendi aggregate'inle değiştir (domain → port → adapter → proto → SQL)
5. `./gradlew :academic-service:bootstrap:build`

---

## 5. ADR şablonu + ilk 6 mimari karar

"Karar yazılı" prensibi: kalıcı mimari kararlar tartışma kanallarında değil,
numaralı ve değişmez kayıtlarda yaşar. Şablon: `docs/adr/_TEMPLATE.md`; ilk kayıt: [ADR-0001](../adr/0001-mono-repo.md).

| ADR | Karar |
|---|---|
| 0001 | Mono-repo (backend + frontend + infra tek repo) |
| 0002 | Java 25 LTS + Spring Boot 4 |
| 0003 | Servisler arası iletişim: gRPC + Protobuf |
| 0004 | Mikroservis topolojisi: shared-lib YOK (bağımsız dağıtım) |
| 0005 | Hexagonal architecture |
| 0006 | Build aracı: Gradle (Kotlin DSL) |

Bir karar değişirse eski ADR silinmez; *Superseded* işaretlenir, yeni ADR ona
referans verir. Yeni kalıcı karar alıyorsan önce `adr/_TEMPLATE.md`'yi kopyala.

---

## 6. Apicurio Registry + Protobuf POC

### 6.1 Nedir, hangi problemi çözüyor?

Lumix'te servisler birbirleriyle iki yolla konuşur: **gRPC** (senkron) ve **Kafka**
(asenkron). İkisi de **tek şema dili** kullanır: **Protobuf** ([ADR-0003](../adr/0003-grpc-protobuf-inter-service.md)).

Problem şu: `identity` servisi bir event'in şemasını değiştirirse, o event'i tüketen
`notification` servisi haberi olmadan kırılabilir — hem de **çalışma zamanında**,
üretimde. Schema registry bunun sigortasıdır: tüm şemalar merkezi bir kayıtta
versiyonlanır ve her yeni versiyon **BACKWARD uyumluluk** kuralından geçmeden kabul
edilmez. BACKWARD = *yeni şemayla yazılan veriyi, eski şemayı bilen consumer okuyabilir.*

### 6.2 Kurulum (`campus/infra/apicurio/`)

Docker Compose ile üç container: **PostgreSQL** (kalıcı şema deposu) +
**Apicurio Registry** (REST API v3, `:8080`) + **Web UI** (`:8888`).

```bash
cd campus/infra/apicurio
cp .env.example .env      # POSTGRES_PASSWORD'ü güçlü bir değerle değiştir!
docker compose --env-file .env up -d
```

Güvenlik ayrıntıları compose'da bilinçli: Postgres portu `127.0.0.1`'e sabit (dışarı
açılmaz), sır `.env`'de (commit edilmez), sürümler pinli (`APICURIO_VERSION=3.3.0`).
İlk kurulumdan sonra **bir kez** global kural set edilir:

```bash
curl -X POST http://localhost:8080/apis/registry/v3/admin/rules \
  -H "Content-Type: application/json" \
  -d '{"ruleType":"COMPATIBILITY","config":"BACKWARD"}'
```

### 6.3 Gradle görevleri (`backend/gradle/schema-registry.gradle.kts`)

Apicurio'nun resmî Gradle plugin'i yok; REST API v3'ü `java.net.http` ile çağıran üç
görev yazıldı (curl/OS bağımlılığı yok — Windows'ta ve Linux CI'da aynı çalışır):

| Görev | Ne yapar |
|---|---|
| `./gradlew schemaValidate` | Tüm `.proto`'ları `dryRun=true` ile dener — **yazmaz**, uyumsuzsa kırar (ön-kontrol) |
| `./gradlew schemaRegister` | Register eder / yeni versiyon açar; BACKWARD ihlali → HTTP 409 → build kırılır |
| `./gradlew schemaSmokeTest` | Dummy proto register + pull + doğrula + temizle (kurulum sağlık testi) |

`-PapicurioUrl=...` veya `APICURIO_URL` env ile hedef registry seçilir.
Hızlı kurulum testi için betik ikizleri de var: `scripts/schema-smoke.sh|.ps1`.

### 6.4 İki katmanlı uyumluluk bekçisi: buf + Apicurio

| Katman | Araç | Ne zaman yakalar |
|---|---|---|
| Derleme/CI zamanı | **buf** (`backend/buf.yaml`; CI job: `schema:validate`) | MR merge olmadan — `buf lint` (stil) + `buf breaking --against main` (wire uyumluluğu) |
| Çalışma zamanı | **Apicurio BACKWARD kuralı** | `schemaRegister` anında — son savunma hattı, 409 ile reddeder |

Neden iki katman? buf, hata geliştiriciye en yakın anda (MR'da) gösterir; Apicurio ise
CI atlansa bile üretime uyumsuz şema girmesini fiziksel olarak engeller.

### 6.5 Adlandırma konvansiyonu (önemli!)

Şema dosya adı = Apicurio artifactId. Kalıp (ayrıntı: `campus/CONTRIBUTING.md`):

```
<service>.<aggregate>.<event>.v<major>            → identity.user.created.v1.proto
```

- Geriye **uyumlu** ekleme (yeni alan, yeni tag) → `v1` kalır, registry'de yeni *version* olur.
- **Kırıcı** değişiklik (alan silme, tag/tip değiştirme) → **yeni dosya** `...v2.proto`;
  `v1` yaşamaya devam eder, consumer'lar kendi hızında geçer.
- Alan tag numarası **asla** değiştirilmez/yeniden kullanılmaz; silmek yerine `reserved`.

### 6.6 Codegen: `.proto` → Java

Codegen buf'ta değil, **protobuf-gradle-plugin**'dedir (`adapter-grpc/build.gradle.kts`):
`src/main/proto/*.proto` dosyaları `./gradlew build` sırasında
`build/generated/source/proto` altına Java + gRPC stub üretir. Üretilen kod
Spotless/Checkstyle denetiminden bilinçli olarak hariçtir (bizim yazdığımız kod değil).

---

## 7. Sprint kapanış durumu ve açık işler

- ✅ Toolchain, git iş akışı, GitLab + CI, service template, 6 ADR — bitti.
- 🔄 **Apicurio + Protobuf POC** — kurulum ve otomasyon repo'da hazır; sunucuda
  BACKWARD kuralının set edilip smoke testin koşulması bekleniyor (ClickUp: *in progress*).
- ⏳ **GitLab restore drill** — hiç başlanmadı (ClickUp: *to do*).

## 8. Yeni geliştirici: nereden başlamalı?

1. Bu sayfayı oku (yaptın ✔)
2. **[Kod Rehberi serisini](../24-codebase-guide/01-repo-haritasi.md) sırayla oku** —
   repo haritası → Gradle → service-template dosya dosya → konfigürasyon → kalite/git.
   Kod yapısını sıfırdan öğreten esas seri budur.
3. `mise install` + `bash scripts/setup-git.sh` (§1, §2)
4. Mimari zihin modeli için: [Hexagonal Architecture](../02-architecture-patterns/03-hexagonal-architecture.md) → [ADR'ler](../adr/0001-mono-repo.md)
5. `cd campus/backend && ./gradlew check` — her şey yeşilse ortamın hazır
6. İlk işini alırken: `scripts/new-branch.sh` ile başla (§2), servis türeteceksen §4.7

## Sözlük

| Terim | Kısa tanım |
|---|---|
| **Trunk-based** | Herkesin kısa ömürlü branch'lerle sık sık `main`'e döndüğü git stratejisi |
| **Conventional Commits** | `tip(kapsam): konu` biçiminde makine-okunur commit mesaj standardı |
| **BOM** | Bill of Materials — uyumlu bağımlılık sürümlerini tek yerden pinleyen liste |
| **Version catalog** | Gradle'ın merkezi bağımlılık/sürüm tanım dosyası (`libs.versions.toml`) |
| **Port / Adapter** | Port: çekirdeğin dış dünyaya açtığı arayüz; Adapter: o arayüzün teknoloji-özel implementasyonu |
| **Aggregate** | Birlikte tutarlı kalması gereken domain nesneleri kümesi; tek giriş kapısı aggregate root |
| **ArchUnit** | Mimari kuralları JUnit testi olarak yazdıran kütüphane |
| **Distroless** | İçinde shell/paket yöneticisi olmayan minimal container imajı |
| **jlink** | Uygulamanın kullandığı JDK modüllerinden küçük özel JRE üreten araç |
| **Schema registry** | Mesaj/RPC şemalarını merkezi versiyonlayan ve uyumluluğu denetleyen servis |
| **BACKWARD** | Yeni şemayla yazılan veriyi eski consumer'ın okuyabilmesi kuralı |
| **buf** | Protobuf lint + breaking-change denetim aracı (CI katmanı) |
| **Testcontainers** | Testte gerçek Postgres/Kafka'yı Docker'da ayağa kaldıran kütüphane |
| **Flyway** | Versiyonlu SQL migration aracı (`V1__...sql`); şemanın tek sahibi |
