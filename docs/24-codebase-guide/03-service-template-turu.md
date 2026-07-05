---
title: "3 · Service Template Turu — dosya dosya"
description: "7 modül, ~30 dosya: her Java dosyasının ne yaptığı, bir HTTP isteğinin katman katman yolculuğu ve yeni özellik eklerken izlenecek sıra."
sidebar_position: 3
---

# Service Template Turu — Dosya Dosya

## Bu sayfa ne anlatıyor?

`backend/service-template/` altındaki **her dosyayı** tek tek gezeceğiz: ne yapar,
neden orada, neyi yapamazsın. Sonra bir HTTP isteğinin kod içindeki yolculuğunu adım
adım izleyeceğiz. Bitirdiğinde "yeni alan ekle", "yeni endpoint aç", "yeni tablo
oluştur" gibi işleri hangi dosyada yapacağını bileceksin.

Ön okuma: [Hexagonal Architecture](../02-architecture-patterns/03-hexagonal-architecture.md)
(kavramsal temel) — bu sayfa o kavramların repodaki **somut hâli**.

## 0. Zihin haritası

Şablon, örnek bir iş nesnesi üzerine kurulu: **`Sample`** (taslak oluşturulur,
aktive edilir, arşivlenir). Gerçek servis türetirken `Sample` dilimini kendi
aggregate'inle değiştirirsin; iskeletin geri kalanı aynen kalır.

```
                    DIŞ DÜNYA (HTTP, gRPC, Kafka, DB)
        ┌──────────────┬──────────────┬──────────────┬───────────────────┐
        │ adapter-rest │ adapter-grpc │ adapter-kafka│ adapter-persistence│
        │  (in)        │  (in)        │ (in + out)   │  (out)            │
        └──────┬───────┴──────┬───────┴──────┬───────┴────────┬──────────┘
               │  in-port'u ÇAĞIRIR          │   out-port'u UYGULAR
        ┌──────▼─────────────────────────────▼────────────────▼──────────┐
        │ application  —  port.in (use case arayüzleri + command)        │
        │                 port.out (repository, event publisher arayüzü) │
        │                 service  (use case implementasyonları)         │
        └──────────────────────────────┬─────────────────────────────────┘
                                       │ yalnızca domain'i bilir
        ┌──────────────────────────────▼─────────────────────────────────┐
        │ domain  —  Sample, SampleId, SampleStatus, event'ler, exception│
        │            SAF JAVA: Spring/JPA/Kafka YOK                      │
        └────────────────────────────────────────────────────────────────┘
                 bootstrap: hepsini toplar, konfigüre eder, çalıştırır
```

Ok yönü kanunu: **bağımlılık her zaman içeri** (adapter → application → domain).
Bunu iki mekanizma zorlar: Gradle modül sınırları (yanlış import = derlenmez) ve
ArchUnit testleri (paket kuralı ihlali = test kırmızı).

---

## 1. `domain/` — iş kurallarının evi (saf Java)

`domain/build.gradle.kts` **bilerek boştur** — tek bağımlılık JDK. Spring, JPA,
Kafka buraya giremez. Neden? İş kuralın framework'ten bağımsız kalırsa: unit test
milisaniyede koşar, framework değişimi domain'i etkilemez, kural tek yerde yaşar.

### `model/Sample.java` — aggregate root

```java
public final class Sample {
    private final SampleId id;
    private String name;
    private SampleStatus status;
    private final List<DomainEvent> domainEvents = new ArrayList<>();

    /** Yeni Sample üretir ve bir SampleCreatedEvent biriktirir. */
    public static Sample create(SampleId id, String name, Instant createdAt) {
        Sample sample = new Sample(id, name, SampleStatus.DRAFT, createdAt);
        sample.domainEvents.add(new SampleCreatedEvent(id, createdAt));
        return sample;
    }

    /** Kalıcı depodan yeniden kurar — event ÜRETMEZ. */
    public static Sample rehydrate(SampleId id, String name, SampleStatus status, Instant createdAt) { ... }

    public void activate() {
        if (status == SampleStatus.ACTIVE) {
            throw new SampleAlreadyActiveException(id);   // ← İŞ KURALI (invariant)
        }
        this.status = SampleStatus.ACTIVE;
    }
}
```

Buradan öğrenilecek kalıplar (yeni aggregate yazarken kopyala):

- **`new` yok, factory var:** `Sample.create(...)` "yeni iş nesnesi doğdu" demek —
  event üretir. `Sample.rehydrate(...)` "DB'den geri yükledim" demek — event üretmez.
  İkisini ayırmazsan her DB okuması sahte "created" event'i fırlatır.
- **Invariant'lar metodun içinde:** "ACTIVE olan tekrar aktive edilemez" kuralı
  controller'da if ile değil, **domain metodunda** exception ile korunur. Kuralı
  atlatmanın yolu yoktur — hangi adapter çağırırsa çağırsın.
- **Event biriktirme:** aggregate olan biteni `domainEvents` listesinde biriktirir;
  yayınlamayı application katmanı yapar (aggregate Kafka'yı bilmez).

### Diğer domain dosyaları

| Dosya | Ne yapar | Kalıp |
|---|---|---|
| `model/SampleId.java` | `UUID`'yi saran record: `SampleId.newId()`, `SampleId.of("...")` | **Value object**: çıplak `UUID` yerine tip — `SampleId` ile `UserId`'yi karıştıramazsın, derleyici yakalar |
| `model/SampleStatus.java` | `DRAFT / ACTIVE / ARCHIVED` enum'u | Yaşam döngüsü durumları string değil enum |
| `event/DomainEvent.java` | Tüm event'lerin arayüzü: `occurredAt()`, `eventType()` | Ortak sözleşme sayesinde publisher tek tip konuşur |
| `event/SampleCreatedEvent.java` | `record ... implements DomainEvent`, tip adı `"sample.created"` | Event = geçmişte olmuş gerçek; adı geçmiş zaman |
| `exception/SampleAlreadyActiveException.java` | Invariant ihlali exception'ı, `RuntimeException` | Domain exception'ları adapter-rest'te HTTP koduna çevrilir (409) |

### `SampleTest.java` — domain testi nasıl yazılır

```java
@Test
void ikinci_activate_invariant_ihlali_atar() {
    Sample sample = Sample.create(SampleId.newId(), "Alpha", FIXED);
    sample.activate();
    assertThatThrownBy(sample::activate).isInstanceOf(SampleAlreadyActiveException.class);
}
```

Spring yok, mock yok, DB yok — saf nesne testi. Test metodu adları Türkçe
snake_case'tir (BDD okunurluğu; Checkstyle testlerde buna izin verecek şekilde ayarlı).

---

## 2. `application/` — use case orkestrasyonu ve port'lar

İzin verilen tek framework parçası: Spring'in `@Service`/`@Transactional`
anotasyonları (pragmatik karar). Web/JPA/Kafka burada da yasak.

### `port/in/` — dünyaya açılan kapılar (use case arayüzleri)

```java
/** Inbound port: adapter'ların çağırabileceği TEK kapı. */
public interface CreateSampleUseCase {
    SampleId create(CreateSampleCommand command);
}

/** Giriş modeli — kendi kendini doğrular. */
public record CreateSampleCommand(String name) {
    public CreateSampleCommand {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name zorunlu");
    }
}
```

`GetSampleUseCase` de aynı kalıpta (`Optional<Sample> byId(SampleId)`).
Neden arayüz? REST de gRPC de **aynı** use case'i çağırır (aşağıda göreceksin) —
iş mantığı iki kez yazılmaz.

### `port/out/` — çekirdeğin dış dünyadan İSTEDİKLERİ

```java
public interface SampleRepository {          // "beni sakla/yükle" — NASIL olduğu umrumda değil
    Sample save(Sample sample);
    Optional<Sample> findById(SampleId id);
}
public interface DomainEventPublisher {      // "bu olayı duyur" — Kafka mı RabbitMQ mu bilmem
    void publish(DomainEvent event);
}
```

Dikkat: imzalarda **domain tipleri** var (`Sample`, `SampleId`) — `SampleJpaEntity`
veya `ProducerRecord` değil. Teknoloji sızıntısı yok.

### `service/` — use case implementasyonları

```java
@Service
@Transactional                       // ← transaction sınırı use case'tir
public class CreateSampleService implements CreateSampleUseCase {
    private final SampleRepository repository;          // out-port (arayüz!)
    private final DomainEventPublisher eventPublisher;  // out-port
    private final Clock clock;                          // test edilebilir zaman

    @Override
    public SampleId create(CreateSampleCommand command) {
        Sample sample = Sample.create(SampleId.newId(), command.name(), clock.instant());
        repository.save(sample);
        sample.domainEvents().forEach(eventPublisher::publish);
        sample.clearDomainEvents();
        return sample.id();
    }
}
```

- Sıra hep aynıdır: **domain'i çalıştır → kaydet → event yayınla**. İş kuralı burada
  YOK (o `Sample.create` içinde); burası yalnızca koordinasyon.
- `GetSampleService` okuma tarafı: `@Transactional(readOnly = true)` — okumalar için
  daha ucuz.
- `Clock` neden enjekte? `Instant.now()` yazsaydık zamana bağlı testler yazılamazdı.
  Testte `Clock.fixed(...)` verilir (bkz. `CreateSampleServiceTest`).

### `CreateSampleServiceTest.java` — Mockito ile port'ları taklit et

```java
@Mock private SampleRepository repository;        // gerçek DB yok
@Mock private DomainEventPublisher eventPublisher; // gerçek Kafka yok

@Test
void olusturur_kaydeder_ve_event_yayinlar() {
    when(repository.save(any(Sample.class))).thenAnswer(inv -> inv.getArgument(0));
    CreateSampleService service = new CreateSampleService(repository, eventPublisher, clock);
    SampleId id = service.create(new CreateSampleCommand("Alpha"));
    verify(eventPublisher).publish(any(SampleCreatedEvent.class));
}
```

Port'lar arayüz olduğu için mock'lamak bedava — Spring context'siz, milisaniyelik test.

---

## 3. `adapter-rest/` — inbound HTTP

| Dosya | Ne yapar |
|---|---|
| `SampleController.java` | `/api/v1/samples` endpoint'leri; yalnızca in-port çağırır |
| `dto/CreateSampleRequest.java` | `record(@NotBlank @Size(max=200) String name)` — HTTP girdi doğrulama |
| `dto/SampleResponse.java` | `record(UUID id, String name, String status)` — çıktı modeli |
| `mapper/SampleRestMapper.java` | `Sample` (domain) → `SampleResponse` (DTO) dönüşümü |
| `ApiExceptionHandler.java` | Domain exception → RFC 7807 `ProblemDetail` çevirisi |

```java
@PostMapping
public ResponseEntity<SampleResponse> create(@RequestBody @Valid CreateSampleRequest request) {
    SampleId id = createSampleUseCase.create(new CreateSampleCommand(request.name()));
    // Yanıt kalıcı hâlden okunur — domain'in normalize ettiği değerler döner,
    // adapter domain durumu hakkında varsayım yapmaz.
    SampleResponse body = getSampleUseCase.byId(id)
            .map(SampleRestMapper::toResponse)
            .orElseThrow(...);
    return ResponseEntity.created(URI.create("/api/v1/samples/" + id.value())).body(body);
}
```

Kavraman gereken ayrımlar:

- **DTO ≠ domain modeli.** `SampleResponse`'u değil `Sample`'ı dışarı versek, iç
  modelde her değişiklik API sözleşmesini kırardı. DTO bir sözleşmedir; domain
  serbestçe evrilir.
- **İki doğrulama katmanı bilinçli:** DTO'daki `@NotBlank` HTTP sınırında hızlı ret
  (400 + düzgün mesaj); `CreateSampleCommand`'daki guard ise adapter hangisi olursa
  olsun çalışan son savunma. Ayrıntı: [Validation Strategy](../03-backend/04-validation-strategy.md).
- **Hata çevirisi tek yerde:** `@RestControllerAdvice` sınıfı
  `SampleAlreadyActiveException` → 409, `IllegalArgumentException` → 400 çevirir;
  gövdeler RFC 7807 formatındadır ([ayrıntı](../03-backend/05-error-handling-rfc7807.md)).
  Controller'larda try/catch görmezsin.

---

## 4. `adapter-grpc/` — inbound gRPC + codegen

| Dosya | Ne yapar |
|---|---|
| `src/main/proto/sample.proto` | Şema: `SampleService.CreateSample(name) → id`. **Elle yazdığın tek şey budur** |
| `SampleGrpcService.java` | Üretilen `SampleServiceImplBase`'i extend eder, aynı in-port'u çağırır |
| `build.gradle.kts` | protobuf-gradle-plugin: `.proto` → Java üretimi + gRPC/protobuf BOM hizalaması |

```protobuf title="sample.proto"
syntax = "proto3";
package lumix.template.v1;
option java_package = "com.lumix.template.grpc.v1";

service SampleService {
  rpc CreateSample (CreateSampleRequest) returns (CreateSampleResponse);
}
message CreateSampleRequest { string name = 1; }   // "= 1" alan TAG'idir — asla değiştirme!
message CreateSampleResponse { string id = 1; }
```

`./gradlew build` sırasında bu dosyadan `build/generated/source/proto/` altına
`CreateSampleRequest`, `SampleServiceGrpc` gibi Java sınıfları üretilir. Üretilen koda
**dokunmazsın** (zaten `build/` altında — git'e girmez, formatlanmaz, denetlenmez).

```java
@GrpcService   // net.devh starter'ı bunu görüp gRPC server'a (:9090) kaydeder
public class SampleGrpcService extends SampleServiceGrpc.SampleServiceImplBase {
    @Override
    public void createSample(CreateSampleRequest request, StreamObserver<CreateSampleResponse> responseObserver) {
        SampleId id = createSampleUseCase.create(new CreateSampleCommand(request.getName()));
        responseObserver.onNext(CreateSampleResponse.newBuilder().setId(id.value().toString()).build());
        responseObserver.onCompleted();
    }
}
```

Kritik gözlem: gövde `SampleController.create` ile **aynı use case'i** çağırıyor.
Hexagonal'ın vaadi tam olarak bu — protokol eklemek iş mantığına dokunmaz.
Şema adlandırma/versiyonlama kuralları: `campus/CONTRIBUTING.md` ve
[Sprint 0 §6.5](../sprint-implementations/sprint-0-hazirlik-ve-toolchain.md).

---

## 5. `adapter-kafka/` — inbound consumer + outbound publisher

| Dosya | Yön | Ne yapar |
|---|---|---|
| `out/kafka/KafkaDomainEventPublisher.java` | out | `DomainEventPublisher` port'unun Kafka implementasyonu |
| `in/kafka/SampleEventConsumer.java` | in | `@KafkaListener` — şablonda yalnızca log'lar (placeholder) |
| `kafka/KafkaTopicsProperties.java` | — | `lumix.kafka.*` ayarlarının tip-güvenli record'u |

```java
@Component
public class KafkaDomainEventPublisher implements DomainEventPublisher {
    public KafkaDomainEventPublisher(KafkaTemplate<String, String> kafkaTemplate, KafkaTopicsProperties topics) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topics.domainEventsTopic();   // topic adı application.yml'den, tip-güvenli
    }
    @Override
    public void publish(DomainEvent event) {
        kafkaTemplate.send(topic, event.eventType(), event.occurredAt().toString());
    }
}
```

```java
@ConfigurationProperties(prefix = "lumix.kafka")   // application.yml → record binding
public record KafkaTopicsProperties(String domainEventsTopic, String inboundTopic) {}
```

Bilinçli şablon kararları (gerçek serviste değişecekler):

- **Outbox yok:** publisher doğrudan `send` ediyor. DB commit olur ama Kafka düşerse
  event kaybolur. Üretim deseninde event aynı transaction'da **outbox tablosuna**
  yazılır, ayrı bir relay Kafka'ya iletir — Sprint 5'in konusu
  ([Outbox Pattern](../02-architecture-patterns/06-outbox-pattern.md)).
- **Consumer placeholder:** gerçek serviste mesajı deserialize edip **bir in-port'a**
  çevirirsin (REST controller'ın yaptığının Kafka versiyonu); şablonda sadece log var.

---

## 6. `adapter-persistence/` — outbound JPA + Flyway

| Dosya | Ne yapar |
|---|---|
| `SampleJpaEntity.java` | `@Entity @Table(name="sample")` — **domain'den AYRI** persistence modeli |
| `SampleJpaRepository.java` | `extends JpaRepository<SampleJpaEntity, UUID>` — Spring Data'nın teknik arayüzü |
| `SampleEntityMapper.java` | domain ↔ entity dönüşümü (`toEntity` / `toDomain`, package-private) |
| `SampleRepositoryAdapter.java` | Asıl adapter: `SampleRepository` **port'unu** JPA ile uygular |
| `db/migration/V1__create_sample_table.sql` | Flyway migration — şemanın tek sahibi |

```java
@Component
public class SampleRepositoryAdapter implements SampleRepository {   // ← out-port implementasyonu
    public Sample save(Sample sample) {
        SampleJpaEntity saved = jpaRepository.save(SampleEntityMapper.toEntity(sample));
        return SampleEntityMapper.toDomain(saved);
    }
}
```

**"Neden iki model?"** — en sık sorulan soru. `Sample`'a doğrudan `@Entity` koysaydık:
domain'e JPA sızardı (boş constructor, mutable alanlar, lazy-loading tuzakları) ve
"framework'süz domain" biterdi. Mapper 10 satırlık angarya gibi görünür ama domain'in
özgürlüğünün bedelidir — şablonda hazır, kopyala.

**Şema yönetimi:** tabloyu Hibernate DEĞİL, **Flyway** oluşturur
(`V1__create_sample_table.sql`); Hibernate yalnızca doğrular (`ddl-auto: validate`).
Yeni tablo/kolon = yeni `V<sıradaki>__aciklama.sql` dosyası. Uygulanmış migration
dosyasını **asla düzenleme** — Flyway checksum tutar, düzenlersen açılışta kırılır.

---

## 7. `bootstrap/` — hepsini birleştiren çalıştırılabilir modül

| Dosya | Ne yapar |
|---|---|
| `TemplateServiceApplication.java` | `@SpringBootApplication @ConfigurationPropertiesScan` — giriş noktası |
| `config/ClockConfig.java` | `Clock.systemUTC()` bean'i (application katmanının istediği zaman kaynağı) |
| `resources/application*.yml` | Konfigürasyon — [sonraki sayfanın](04-konfigurasyon-ve-calistirma.md) konusu |
| `resources/logback-spring.xml` | dev: okunur konsol; diğer profiller: tek satır JSON log |
| `test/architecture/HexagonalArchitectureTest.java` | ArchUnit: bağımlılık yönü kuralları (3 kural) |
| `test/SmokeIntegrationTest.java` | Testcontainers: gerçek Postgres'le context ayağa kalkıyor mu? |
| `build.gradle.kts` | Tüm adapter'ları toplar; actuator + prometheus + JSON log; `bootJar` → `app.jar` |
| (kökte) `Dockerfile` | 3 aşama: bootJar → jlink JRE → distroless imaj |

Neden ayrı modül? Çünkü **yalnızca bootstrap her şeyi görür**. Adapter'lar birbirini
göremez (REST, Kafka'yı import edemez — derlenmez). Bu da mimariyi korur: "kestirme"
fiziksel olarak imkânsız.

`SmokeIntegrationTest` `@Tag("integration")` taşır: gerçek Postgres container'ı
kaldırır, Flyway'i koşturur, `/actuator/health/readiness`'in `UP` dönmesini bekler.
`./gradlew check -Pintegration` ile koşar (Docker gerekir) — DI/otokonfig hatalarını
yakalayan **tek** test katmanıdır.

---

## 8. Bir isteğin yolculuğu — uçtan uca {#istegin-yolculugu}

`POST /api/v1/samples {"name":" Alpha "}` geldiğinde sırasıyla:

```
 1. Tomcat (virtual thread üzerinde) isteği alır
 2. SampleController.create çağrılır
    → @Valid: CreateSampleRequest doğrulanır ("  " olsaydı: 400 ProblemDetail)
 3. Controller CreateSampleCommand üretir → CreateSampleUseCase.create(...)   [PORT]
 4. CreateSampleService (@Transactional → transaction AÇILIR)
 5. Sample.create(...)  → isim normalize edilir ("Alpha"), status=DRAFT,
    SampleCreatedEvent biriktirilir                                  [DOMAIN]
 6. repository.save(sample)                                          [OUT-PORT]
    → SampleRepositoryAdapter → SampleEntityMapper.toEntity → JPA INSERT
 7. eventPublisher.publish(event)                                    [OUT-PORT]
    → KafkaDomainEventPublisher → kafkaTemplate.send("sample.domain-events", ...)
 8. Transaction COMMIT, use case SampleId döner
 9. Controller GetSampleUseCase.byId(id) ile kalıcı hâli okur
    → SampleRestMapper.toResponse → SampleResponse
10. HTTP 201 Created + Location: /api/v1/samples/<id> + JSON gövde
```

Hata senaryosu: 5. adımda invariant ihlali olsaydı `SampleAlreadyActiveException`
fırlar → transaction ROLLBACK → `ApiExceptionHandler` onu **409 Conflict**
ProblemDetail'e çevirir. Hiçbir katmanda try/catch yazmadık.

## 9. Yeni özellik eklerken izlenecek sıra {#yeni-ozellik}

Örnek iş: "Sample'a `archive()` özelliği ekle, REST'ten çağrılabilsin."
**Her zaman içeriden dışarı** yazılır:

1. **domain** — `Sample.archive()` metodu + gerekiyorsa invariant + `SampleArchivedEvent`
2. **domain testi** — `SampleTest`'e `arsivlenir()` / ihlal senaryosu
3. **application** — `ArchiveSampleUseCase` (port/in) + `ArchiveSampleService` + Mockito testi
4. **adapter** — `SampleController`'a `@PostMapping("/{id}/archive")`; gerekiyorsa
   `ApiExceptionHandler`'a yeni çeviri
5. (şema değişiyorsa) **persistence** — yeni `V<n>__*.sql` migration + entity/mapper güncelle
6. `./gradlew check` → `check -Pintegration` → commit

Bu sıranın güzelliği: 1-3. adımlar hiçbir framework bilgisi istemez; işin özü
tamamlandıktan sonra adapter'lar sadece "kablolama"dır.

## 10. Yeni servis türetme {#yeni-servis}

Özet akış (tam komutlar: `backend/service-template/README.md`):

```
1. cp -r service-template academic-service
2. paket adı değiştir: com.lumix.template → com.lumix.academic (sed + git mv)
3. settings.gradle.kts'e 7 modülü include et; iç project(":service-template:...") yollarını güncelle
4. Sample dilimini kendi aggregate'inle değiştir (bu sayfadaki sırayla: domain → app → adapter)
5. ./gradlew :academic-service:bootstrap:build
```

## 11. Sonraki adım

[Konfigürasyon & Çalıştırma](04-konfigurasyon-ve-calistirma.md) — `application.yml`
satır satır, profiller, loglama, Docker imajı ve servisi lokalde ayağa kaldırma.
