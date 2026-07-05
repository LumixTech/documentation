---
title: Hexagonal Architecture (Ports & Adapters)
description: Hexagonal Architecture'ın çekirdek mantığı, adapter-core ayrımı, port-adapter eşlemeleri ve Lumix servislerinde uygulanışı.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Bu sayfa **Hexagonal Architecture** (a.k.a. Ports & Adapters) pattern'ini sıfırdan öğretiyor: amacı, çekirdek (core) ile çevre (adapter) ayrımı, **inbound port / outbound port** kavramları ve Lumix'te her microservice'in bu pattern'i nasıl uyguladığı. Sayfanın sonunda okuyan biri yeni bir feature eklerken **kodunu hangi katmana koyacağını**, validation'ı **nerede yapacağını**, port mu adapter mı yazması gerektiğini söyleyebilmeli.

## 1. Bu nedir? (Sıfırdan)

**Hexagonal Architecture** (1994'te Alistair Cockburn tarafından önerildi, sonradan "Ports and Adapters" diye anıldı), bir uygulamanın **iş mantığını (core)** dış dünyadan (HTTP, DB, message broker, dosya sistemi vs.) **izole etme** yaklaşımıdır.

**Günlük hayattan analoji:**
Bir bilgisayar düşün. **Anakart (core)** asıl hesaplamayı yapar. Ama anakartın klavyeyle direkt iletişimi yoktur — arada **USB portu** vardır. Yarın klavye değişir (kablolu → kablosuz, USB-A → USB-C), anakart değişmez. Anakart sadece "klavye buradan girer" portunu bilir; o portun arkasına ne taktığın anakartı ilgilendirmez.

Hexagonal Architecture aynısını söyler:
- **Core (anakart)** = iş mantığı, domain model, use case'ler — saf, framework'siz
- **Port (USB portu)** = core'un dış dünya ile konuştuğu interface
- **Adapter (klavye, fare, monitor)** = portun arkasına takılan somut implementasyon (REST controller, JPA repository, Kafka consumer vs.)

**İki tür port var:**

1. **Inbound port (driving port / primary port):**
   - Dış dünyadan core'a giren çağrılar
   - Use case interface'leri (`MarkAttendanceUseCase`, `RegisterUserUseCase`)
   - "Bana şu iş kabiliyetini sun"

2. **Outbound port (driven port / secondary port):**
   - Core'un dış dünyaya ihtiyaç duyduğu yetenekler
   - Repository, message publisher, external service client (`AttendanceRepository`, `OutboxPublisher`, `OrganizationServiceClient`)
   - "Şuna ihtiyacım var, kim verirse versin"

```
                  ┌──────── INBOUND ADAPTERS ────────┐
                  │  REST Controller                  │
                  │  gRPC Service                     │
                  │  Kafka Consumer                   │
                  │  Scheduled Job                    │
                  └─────────────┬─────────────────────┘
                                │ uses
                                ▼
              ┌────────── INBOUND PORTS ────────┐
              │  MarkAttendanceUseCase           │
              │  RevisAttendanceUseCase          │
              └─────────────┬────────────────────┘
                            │ implemented by
                            ▼
        ╔═══════════════════════════════════════════╗
        ║              APPLICATION CORE              ║
        ║                                            ║
        ║  ┌────────── DOMAIN MODEL ────────────┐  ║
        ║  │   Attendance (aggregate root)       │  ║
        ║  │   StudentMark, PresenceStatus       │  ║
        ║  │   Domain events                     │  ║
        ║  └─────────────────────────────────────┘  ║
        ║                                            ║
        ║  ┌─────── USE CASE SERVICES ──────────┐  ║
        ║  │   MarkAttendanceService             │  ║
        ║  │   GetAttendanceService              │  ║
        ║  └─────────────────────────────────────┘  ║
        ╚═══════════════════════════════════════════╝
                            │ depends on
                            ▼
              ┌──────── OUTBOUND PORTS ─────────┐
              │  AttendanceRepository            │
              │  OutboxEventPublisher            │
              │  OrganizationServiceClient       │
              └─────────────┬────────────────────┘
                            │ implemented by
                            ▼
                ┌────── OUTBOUND ADAPTERS ───────┐
                │  JpaAttendanceRepository        │
                │  KafkaOutboxPublisher           │
                │  GrpcOrganizationClient         │
                └─────────────────────────────────┘
```

Hexagonal Architecture neden **hexagon (altıgen)** çizilir? Çünkü Cockburn pattern'i tanıttığında "bir uygulamanın **6 tarafı** olabilir; HTTP, mesaj kuyruğu, DB, dosya, scheduler, GUI" demek istedi. Şekil önemli değil — önemli olan **core'un dış dünyaya bağımlı olmaması**.

## 2. Hangi problemi çözüyor?

Hexagonal Architecture olmadan klasik **layered architecture** (controller → service → repository) ile başlarsın. Başlangıçta sade, ama büyüdükçe şu acılar gelir:

**Acı 1 — Domain framework'e bağlanır.**
`AttendanceService` Spring'e bağımlı (`@Service`, `@Transactional`), JPA'ya bağımlı (`@PersistenceContext`), MapStruct'a bağımlı. Domain logic'i test etmek için Spring context yüklemek gerekir, test 30 saniye sürer.

**Acı 2 — Adapter değiştirmek kabusa döner.**
"REST'i gRPC'ye taşıyoruz" denir. Controller'lar değişecek + service'lerin signature'ları değişecek + DTO'lar değişecek + 200 yerde refactor. Çünkü domain kullanılan DTO'ya bağımlı.

**Acı 3 — DB değişikliği domain'i kırmaz mı?**
PostgreSQL'den MongoDB'ye geçmek (veya tersi) imkansız hale gelir, çünkü domain doğrudan JPA Entity yapısına bağımlı.

**Acı 4 — Validation kaosu.**
- Controller'da `@Valid` (HTTP input validation)
- Service'te tekrar `if (x == null)`
- Domain'de tekrar invariant check
- DB constraint
Aynı kural 4 yerde, biri kaçınca bug.

**Acı 5 — Unit test imkansızlaşır.**
"Bu method'u test etmek için Spring context lazım, DB lazım, Kafka lazım, mock kütüphanesi lazım..." Sonuç: unit test yazılmaz, sadece integration test kalır, CI yavaşlar.

**Acı 6 — Side effect'ler her yere yayılır.**
Service'in içinde bir yerde Kafka publish, başka yerde HTTP call, başka yerde email send. Test ederken bunları nasıl mock edeceksin? Üretimde sıraları nasıl garanti edeceksin?

Hexagonal bu acıları şöyle çözer:

| Acı | Hexagonal çözümü |
|---|---|
| Domain framework'e bağlanır | Core saf Java, framework dependency yok |
| Adapter değiştirmek zor | Port interface stabil, adapter sınırda değişir |
| DB değişikliği domain'i kırar | Repository **interface** domain'de, JPA implementation adapter'da |
| Validation kaosu | Adapter'da input validation, core'da invariant check — net rol ayrımı |
| Unit test imkansız | Use case service plain Java + mock'lanmış port'larla test edilir |
| Side effect kaosu | Tüm outbound port'lar açıkça tanımlı, mock'lanabilir |

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Dependency yönü kuralı

Hexagonal'ın **tek kuralı** vardır: **bağımlılık her zaman içeriye doğrudur**.

```
adapter → application → domain
   ✗ tersi yasak
```

Yani:
- `domain/` `application/` ve `adapter/` paketlerini import edemez
- `application/` `adapter/` paketini import edemez
- `adapter/` her ikisini import edebilir

Bu kural CI'da otomatik denetlenir (ArchUnit, jQAssistant, Spring Modulith).

### 3.2. Port — interface tanımı

Port bir **Java interface**'dir, domain veya application katmanında durur.

**Inbound port örneği:**

```java
// application/port/in/MarkAttendanceUseCase.java
public interface MarkAttendanceUseCase {
    AttendanceId execute(MarkAttendanceCommand command);
}

// application/port/in/MarkAttendanceCommand.java (input model)
public record MarkAttendanceCommand(
    ClassId classId,
    LocalDate date,
    List<StudentMarkInput> marks,
    UUID tenantId
) {}
```

**Outbound port örneği:**

```java
// application/port/out/AttendanceRepository.java
public interface AttendanceRepository {
    void save(Attendance attendance);
    Optional<Attendance> findById(AttendanceId id);
    Optional<Attendance> findByClassAndDate(ClassId classId, LocalDate date);
}

// application/port/out/OutboxEventPublisher.java
public interface OutboxEventPublisher {
    void publish(DomainEvent event);
}
```

### 3.3. Adapter — port'u implement eden somut sınıf

**Inbound adapter (REST controller):**

```java
// adapter/in/rest/AttendanceController.java
@RestController
@RequestMapping("/api/v1/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final MarkAttendanceUseCase markAttendanceUseCase;

    @PostMapping
    public ResponseEntity<AttendanceResponse> mark(@RequestBody @Valid MarkAttendanceRequest req) {
        MarkAttendanceCommand cmd = AttendanceRestMapper.toCommand(req);
        AttendanceId id = markAttendanceUseCase.execute(cmd);
        return ResponseEntity.created(URI.create("/api/v1/attendance/" + id.value()))
            .body(new AttendanceResponse(id.value()));
    }
}
```

**Outbound adapter (JPA repository implementation):**

```java
// adapter/out/persistence/JpaAttendanceRepository.java
@Repository
@RequiredArgsConstructor
public class JpaAttendanceRepository implements AttendanceRepository {
    private final EntityManager em;

    @Override
    public void save(Attendance attendance) {
        if (em.contains(attendance)) {
            em.merge(attendance);
        } else {
            em.persist(attendance);
        }
    }
    // ...
}
```

### 3.4. Validation katmanlama

Hexagonal Architecture'da **validation iki katmanda** yapılır:

**Katman 1 — Adapter (input validation):**
- "Bu HTTP isteğindeki email alanı dolu mu? Format doğru mu?"
- "Bu gRPC request'inde class_id UUID mi?"
- Mekanizma: Jakarta Bean Validation (`@Valid`, `@NotNull`, `@Pattern`, `@Email`)
- Hata: HTTP 400 / gRPC INVALID_ARGUMENT

**Katman 2 — Domain (invariant check):**
- "Bu sınıfın kapasitesi aşılıyor mu?"
- "Bu yoklama 24 saat sonra revize edilebilir mi?"
- "Bu öğrenci zaten işaretlenmiş mi?"
- Mekanizma: Aggregate root metodlarında `throw new BusinessException(...)`
- Hata: HTTP 409 / 422 / 403, gRPC FAILED_PRECONDITION

Detay: [Validation Strategy](../03-backend/04-validation-strategy.md).

### 3.5. Use case service — port implementation'ı

Use case service inbound port'u implement eder, outbound port'ları kullanır:

```java
// application/service/MarkAttendanceService.java
@Service
@RequiredArgsConstructor
@Transactional
public class MarkAttendanceService implements MarkAttendanceUseCase {

    private final AttendanceRepository attendanceRepo;  // outbound port
    private final OutboxEventPublisher outboxPublisher; // outbound port
    private final Clock clock;

    @Override
    public AttendanceId execute(MarkAttendanceCommand cmd) {
        Attendance attendance = attendanceRepo
            .findByClassAndDate(cmd.classId(), cmd.date())
            .orElseGet(() -> Attendance.create(cmd.classId(), cmd.date(), cmd.tenantId()));

        for (var mark : cmd.marks()) {
            attendance.mark(mark.studentId(), mark.presence()); // invariant burada
        }
        attendance.submit(clock);

        attendanceRepo.save(attendance);
        attendance.domainEvents().forEach(outboxPublisher::publish);
        attendance.clearDomainEvents();

        return attendance.id();
    }
}
```

Hiçbir Spring HTTP, JPA, Kafka import'u yok — sadece **port'lar**. Bu sınıf **plain JUnit** ile test edilebilir.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Lumix klasör yapısı

Her microservice şu yapıyı izler:

```
academic-service/
└── src/main/java/com/lumix/academic/
    ├── domain/                          # Tamamen framework-free
    │   ├── model/
    │   │   ├── Attendance.java          # Aggregate root
    │   │   ├── AttendanceId.java        # VO
    │   │   ├── StudentMark.java         # VO
    │   │   └── PresenceStatus.java      # Enum
    │   ├── event/
    │   │   ├── DomainEvent.java         # Marker interface
    │   │   ├── AttendanceMarkedEvent.java
    │   │   └── AttendanceRevisedEvent.java
    │   └── exception/
    │       ├── AttendanceRevisionWindowExpiredException.java
    │       └── DuplicateStudentMarkException.java
    │
    ├── application/                     # Framework minimal (sadece Spring stereotypes)
    │   ├── port/
    │   │   ├── in/                      # Inbound port (use case)
    │   │   │   ├── MarkAttendanceUseCase.java
    │   │   │   └── MarkAttendanceCommand.java
    │   │   └── out/                     # Outbound port
    │   │       ├── AttendanceRepository.java
    │   │       ├── OutboxEventPublisher.java
    │   │       └── OrganizationServiceClient.java
    │   └── service/
    │       └── MarkAttendanceService.java  # implements MarkAttendanceUseCase
    │
    ├── adapter/                         # Framework heavy
    │   ├── in/
    │   │   ├── rest/                    # HTTP adapter
    │   │   │   ├── AttendanceController.java
    │   │   │   ├── dto/
    │   │   │   │   ├── MarkAttendanceRequest.java
    │   │   │   │   └── AttendanceResponse.java
    │   │   │   └── mapper/
    │   │   │       └── AttendanceRestMapper.java
    │   │   ├── grpc/                    # gRPC adapter
    │   │   │   ├── AttendanceGrpcService.java
    │   │   │   └── mapper/
    │   │   │       └── AttendanceProtoMapper.java
    │   │   └── kafka/                   # Kafka consumer adapter
    │   │       └── ClassAssignmentEventConsumer.java
    │   └── out/
    │       ├── persistence/             # JPA adapter
    │       │   ├── JpaAttendanceRepository.java
    │       │   └── AttendanceJpaEntity.java (opsiyonel ayrı mapping)
    │       ├── kafka/                   # Kafka publisher adapter
    │       │   └── KafkaOutboxPublisher.java
    │       └── grpc/                    # gRPC client adapter
    │           └── GrpcOrganizationServiceClient.java
    │
    └── config/                          # Spring config
        ├── KafkaConfig.java
        ├── GrpcConfig.java
        └── SecurityConfig.java
```

### 4.2. Lumix'te dependency yön kuralı

| Katman | İmport edebileceği paketler |
|---|---|
| `domain/` | Sadece JDK + jakarta.persistence (opsiyonel pragmatik) |
| `application/port/` | `domain/` |
| `application/service/` | `domain/`, `application/port/`, `org.springframework.stereotype` |
| `adapter/in/` | `domain/`, `application/port/in/`, web framework |
| `adapter/out/` | `domain/`, `application/port/out/`, persistence/messaging framework |
| `config/` | Tüm katmanlar |

CI'da **ArchUnit testi** ile bu kural otomatik kontrol edilir:

```java
@AnalyzeClasses(packages = "com.lumix.academic")
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_should_not_depend_on_adapter =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule application_should_not_depend_on_adapter =
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");
}
```

### 4.3. Bir feature eklerken adımlar

Yeni feature: "Yoklamayı PDF olarak indir."

1. **Use case port** tanımla: `ExportAttendancePdfUseCase` (`application/port/in/`)
2. **Use case service** yaz: `ExportAttendancePdfService` (`application/service/`)
3. **Outbound port** lazımsa tanımla: `PdfGenerator` (`application/port/out/`)
4. **Inbound adapter** ekle: REST endpoint (`adapter/in/rest/`)
5. **Outbound adapter** ekle: `ApachePdfBoxPdfGenerator` (`adapter/out/pdf/`)
6. **Test:**
   - Domain unit test (DB'siz)
   - Service unit test (mock'lu port'larla)
   - Adapter integration test (Testcontainers)
   - Contract test (Pact)

### 4.4. Adapter çeşitliliği — bir use case'in çoklu giriş noktası

Aynı use case'in **REST + gRPC** kanalı olabilir:

```java
// REST adapter
@RestController
public class AttendanceController {
    private final MarkAttendanceUseCase markAttendanceUseCase;
    @PostMapping("/api/v1/attendance")
    public ResponseEntity<?> mark(...) {
        markAttendanceUseCase.execute(...);
    }
}

// gRPC adapter — AYNI use case'i çağırır
@GrpcService
public class AttendanceGrpcService extends AttendanceServiceGrpc.AttendanceServiceImplBase {
    private final MarkAttendanceUseCase markAttendanceUseCase;
    @Override
    public void markAttendance(MarkAttendanceRequest req, StreamObserver<MarkAttendanceResponse> obs) {
        AttendanceId id = markAttendanceUseCase.execute(toCommand(req));
        // ...
    }
}
```

Use case **çağıran protokolden habersiz**. REST'i kapatıp sadece gRPC bıraksak, use case değişmez.

### 4.5. Lumix'te framework bağımsızlığı seviyesi

> **Pragmatik yaklaşım:** "Pure" hexagonal `domain/`'i framework'ten **tamamen** ayırır. Lumix bunu **yumuşatıyor**:

- `domain/` katmanında **JPA annotation kabul** (`@Entity`, `@Embeddable`, `@Column`). Sebep: ayrı JpaEntity sınıfları + mapper yazmak gereksiz boilerplate.
- `domain/` Spring annotation'ı **YASAK** (`@Service`, `@Component`).
- `application/service/` Spring annotation kabul (`@Service`, `@Transactional`) — pragmatik.
- `adapter/` her şey kabul.

Bu pragmatik tercih ekibe maliyeti azaltır, %95 hexagonal değerini korur. Pure hexagonal isteyen takımlar JPA entity'yi ayrı sınıf yapar, mapper koyar — Lumix bu maliyeti haklı görmüyor.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

**Alternatif 1 — Klasik 3-layer (Controller → Service → Repository)**
Spring projelerinin default'u.

Niye elendi:
- Domain framework'e doğrudan bağımlı
- Test için Spring context lazım
- DB veya HTTP değişikliği domain'i kırar
- Validation katmanları belirsiz

**Alternatif 2 — Onion Architecture**
Hexagonal'in alternatifi, çok benzer kavramlar. Jeffrey Palermo, 2008.

Niye elendi:
- Hexagonal ile büyük fark yok, sadece terminoloji
- "Port/adapter" terminolojisi Lumix'in iletişiminde daha yerleşmiş

**Alternatif 3 — Clean Architecture (Uncle Bob)**
Robert C. Martin'in versiyonu, hexagonal'in jeneralizasyonu.

Niye elendi:
- Kavramsal olarak aynı
- "Use case" / "interactor" / "presenter" terminolojisi Spring projelerinde fazla geliyor
- Lumix daha pratik hexagonal kullanır

### 5.2. Kabul edilen trade-off'lar

| Trade-off | Maliyet | Lumix tutumu |
|---|---|---|
| **Daha fazla sınıf/dosya** | Port + adapter ayrımı = 2 dosya | Boilerplate kabul, sürdürülebilirlik kazancı büyük |
| **Mapper yazımı** | DTO → command → domain → entity dönüşümleri | MapStruct ile minimize, kabul edilen ücret |
| **Yeni geliştirici eğitimi** | "Port nedir? Adapter nedir?" sorusu | İlk hafta öğrenilir, sonrası refleks |
| **JPA pragmatik** | "Pure DDD" puristleri rahatsız olur | Lumix pure değil, %95 hexagonal yeterli |
| **Architecture test bakımı** | ArchUnit testleri yazılı/güncel tutulmalı | CI'da otomatik = unutulmaz |

### 5.3. Ne zaman gözden geçirilir?

- Eğer Lumix'te servisler **gerçekten** sade CRUD'a indirgenirse (zor görünüyor), hexagonal overkill olabilir.
- Eğer takım hexagonal'i **anlamadan uygularsa**, port/adapter sırf form olur, değer üretmez. O zaman eğitim eksik demektir, mimari değişmemeli.

## 6. Pratik örnek

### 6.1. Tam bir feature — yoklama alma akışı

**Domain (Attendance aggregate):**
Bkz. [Domain-Driven Design — Pratik Örnek](./02-domain-driven-design.md#6-pratik-örnek).

**Inbound port:**

```java
// application/port/in/MarkAttendanceUseCase.java
public interface MarkAttendanceUseCase {
    AttendanceId execute(MarkAttendanceCommand command);
}

// application/port/in/MarkAttendanceCommand.java
public record MarkAttendanceCommand(
    ClassId classId,
    LocalDate date,
    List<StudentMarkInput> marks,
    UUID tenantId
) {
    public record StudentMarkInput(StudentId studentId, PresenceStatus presence) {}
}
```

**Outbound port:**

```java
// application/port/out/AttendanceRepository.java
public interface AttendanceRepository {
    void save(Attendance attendance);
    Optional<Attendance> findByClassAndDate(ClassId classId, LocalDate date);
}

// application/port/out/OutboxEventPublisher.java
public interface OutboxEventPublisher {
    void publish(DomainEvent event);
}
```

**Use case service:**

```java
// application/service/MarkAttendanceService.java
@Service
@RequiredArgsConstructor
@Transactional
public class MarkAttendanceService implements MarkAttendanceUseCase {

    private final AttendanceRepository attendanceRepo;
    private final OutboxEventPublisher outboxPublisher;
    private final Clock clock;

    @Override
    public AttendanceId execute(MarkAttendanceCommand cmd) {
        Attendance attendance = attendanceRepo
            .findByClassAndDate(cmd.classId(), cmd.date())
            .orElseGet(() -> Attendance.create(cmd.classId(), cmd.date(), cmd.tenantId()));

        for (var mark : cmd.marks()) {
            attendance.mark(mark.studentId(), mark.presence());
        }
        attendance.submit(clock);

        attendanceRepo.save(attendance);
        attendance.domainEvents().forEach(outboxPublisher::publish);
        attendance.clearDomainEvents();

        return attendance.id();
    }
}
```

**REST adapter (inbound):**

```java
// adapter/in/rest/AttendanceController.java
@RestController
@RequestMapping("/api/v1/attendance")
@RequiredArgsConstructor
@Validated
public class AttendanceController {

    private final MarkAttendanceUseCase markAttendanceUseCase;

    @PostMapping
    @PreAuthorize("hasPermission(#req.classId(), 'class', 'attendance:write')")
    public ResponseEntity<AttendanceResponse> mark(
        @RequestBody @Valid MarkAttendanceRequest req,
        @AuthenticationPrincipal AuthenticatedUser user
    ) {
        MarkAttendanceCommand cmd = AttendanceRestMapper.toCommand(req, user.tenantId());
        AttendanceId id = markAttendanceUseCase.execute(cmd);
        return ResponseEntity.created(URI.create("/api/v1/attendance/" + id.value()))
            .body(new AttendanceResponse(id.value()));
    }
}
```

**REST DTO + validation:**

```java
// adapter/in/rest/dto/MarkAttendanceRequest.java
public record MarkAttendanceRequest(
    @NotNull UUID classId,
    @NotNull @PastOrPresent LocalDate date,
    @NotEmpty @Valid List<StudentMarkRequest> marks
) {
    public record StudentMarkRequest(
        @NotNull UUID studentId,
        @NotNull PresenceStatus presence
    ) {}
}
```

**gRPC adapter (paralel inbound) — aynı use case:**

```java
// adapter/in/grpc/AttendanceGrpcService.java
@GrpcService
@RequiredArgsConstructor
public class AttendanceGrpcService extends AttendanceServiceGrpc.AttendanceServiceImplBase {

    private final MarkAttendanceUseCase markAttendanceUseCase;

    @Override
    public void markAttendance(
        com.lumix.proto.academic.MarkAttendanceRequest request,
        StreamObserver<com.lumix.proto.academic.MarkAttendanceResponse> responseObserver
    ) {
        MarkAttendanceCommand cmd = AttendanceProtoMapper.toCommand(request);
        AttendanceId id = markAttendanceUseCase.execute(cmd);
        responseObserver.onNext(
            com.lumix.proto.academic.MarkAttendanceResponse.newBuilder()
                .setAttendanceId(id.value().toString())
                .build()
        );
        responseObserver.onCompleted();
    }
}
```

**JPA repository adapter (outbound):**

```java
// adapter/out/persistence/JpaAttendanceRepository.java
@Repository
@RequiredArgsConstructor
public class JpaAttendanceRepository implements AttendanceRepository {

    private final EntityManager em;

    @Override
    public void save(Attendance attendance) {
        if (attendance.id() == null || em.find(Attendance.class, attendance.id()) == null) {
            em.persist(attendance);
        } else {
            em.merge(attendance);
        }
    }

    @Override
    public Optional<Attendance> findByClassAndDate(ClassId classId, LocalDate date) {
        return em.createQuery(
            "SELECT a FROM Attendance a WHERE a.classId = :classId AND a.date = :date",
            Attendance.class
        )
        .setParameter("classId", classId)
        .setParameter("date", date)
        .getResultList()
        .stream()
        .findFirst();
    }
}
```

**Kafka outbox publisher adapter (outbound):**

```java
// adapter/out/kafka/KafkaOutboxPublisher.java
@Component
@RequiredArgsConstructor
public class KafkaOutboxPublisher implements OutboxEventPublisher {

    private final OutboxRepository outboxRepo;

    @Override
    public void publish(DomainEvent event) {
        // SAME transaction → outbox table'a yaz
        OutboxRecord record = OutboxRecord.create(
            event.eventType(),
            event.aggregateId().toString(),
            event.toProtoBytes()
        );
        outboxRepo.save(record);
        // Background relay process Kafka'ya gönderecek
    }
}
```

### 6.2. Use case unit test — DB'siz

```java
class MarkAttendanceServiceTest {

    AttendanceRepository repo = Mockito.mock(AttendanceRepository.class);
    OutboxEventPublisher outbox = Mockito.mock(OutboxEventPublisher.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-27T10:00:00Z"), ZoneOffset.UTC);

    MarkAttendanceService service = new MarkAttendanceService(repo, outbox, clock);

    @Test
    void shouldCreateAndSubmitAttendance() {
        ClassId classId = ClassId.of("c1");
        LocalDate today = LocalDate.now(clock);
        when(repo.findByClassAndDate(classId, today)).thenReturn(Optional.empty());

        AttendanceId result = service.execute(new MarkAttendanceCommand(
            classId, today,
            List.of(new StudentMarkInput(StudentId.of("s1"), PresenceStatus.PRESENT)),
            UUID.randomUUID()
        ));

        assertThat(result).isNotNull();
        verify(repo).save(any(Attendance.class));
        verify(outbox).publish(any(AttendanceMarkedEvent.class));
    }
}
```

Hiçbir Spring context, hiç DB, hiç Kafka. Saniyenin altında çalışır.

### 6.3. ArchUnit ile dependency yön testi

```java
@AnalyzeClasses(packages = "com.lumix.academic")
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domain_does_not_depend_on_application_or_adapter =
        noClasses().that().resideInAPackage("com.lumix.academic.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.lumix.academic.application..",
                "com.lumix.academic.adapter.."
            );

    @ArchTest
    static final ArchRule application_does_not_depend_on_adapter =
        noClasses().that().resideInAPackage("com.lumix.academic.application..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.lumix.academic.adapter..");

    @ArchTest
    static final ArchRule controllers_only_depend_on_use_cases =
        classes().that().resideInAPackage("..adapter.in.rest..")
            .and().areAnnotatedWith(RestController.class)
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                "..application.port.in..",
                "..adapter.in.rest..",
                "java..", "jakarta..", "org.springframework..", "..domain.."
            );
}
```

## 7. Dikkat edilecek tuzaklar

**Tuzak 1 — Anemic Hexagonal.**
Port/adapter ayrımı yapılır ama domain hala anemic (sadece getter/setter). Sonuç: hexagonal şekli, klasik 3-layer mantığı. Tüm logic service'te kalmış.
**Önleme:** DDD ile birleştir — aggregate root metodları, value object'ler, invariant'lar.

**Tuzak 2 — Direkt Entity Sızıntısı.**
REST controller direkt JPA entity'yi döndürür. Bu adapter'dan domain'e (veya tersine) sızıntı yaratır.
**Önleme:** Controller → DTO. Mapper kullan.

**Tuzak 3 — Use Case Service Anemic.**
Service sadece repository çağırır, domain'e iş bırakmaz.
**Önleme:** Service orchestration yapar (transaction sınırı, port koordinasyonu). İş kuralı aggregate'te.

**Tuzak 4 — Çok küçük adapter, çok büyük service.**
Service 500 satır, her detay orada. Adapter ince.
**Önleme:** Service kısa olmalı (10-30 satır), tipik akış: load aggregate → call method → save → publish event.

**Tuzak 5 — Port Stabil Tutulmaması.**
Use case interface her sprint değişir. Adapter'lar sürekli kırılır.
**Önleme:** Port public sözleşme, değişimi commit edilir, breaking change ayrı task.

**Tuzak 6 — Reverse Dependency.**
`adapter` paketinden bir sınıf `domain`'in iç yapısına bel bağlar (örn. JPA entity proxy döner, controller bunun iç metodlarını çağırır).
**Önleme:** ArchUnit testleri + code review.

**Tuzak 7 — DTO Patlaması.**
Her katman için ayrı DTO: `Request`, `Command`, `DomainModel`, `Entity`, `Response`. 5 mapper.
**Önleme:** Sade ol — `Command` ve `Response` çoğu zaman yeter. Lumix'te `Command` = inbound port input, `Response` = inbound adapter output.

**Tuzak 8 — Hexagonal'i sırf moda diye uygulamak.**
Karmaşık olmayan bir CRUD servis için port/adapter ayrımı yapılır, kimse değerini görmez.
**Önleme:** Hexagonal karmaşıklıkla orantılı değer üretir. Sade CRUD'ler için Spring 3-layer yeter — ama Lumix domain'i karmaşık olduğu için hex değerli.

**Tuzak 9 — Outbound Port'un Çok Geniş Olması.**
`AttendanceRepository` içinde `save`, `findAll`, `delete`, `count`, `findByXxx`, `findByYyy`... 30 method. Mock'lamak imkansız.
**Önleme:** Interface Segregation Principle — küçük, focused port'lar. Use case ne lazımsa onu tanımla.

**Tuzak 10 — Test Adapter'larının Yokluğu.**
Use case'i test ederken outbound port'ları her seferinde Mockito ile mock'larsın. Boilerplate birikir.
**Önleme:** Test-only adapter'lar yaz: `InMemoryAttendanceRepository` gibi. Use case test'leri daha okunaklı olur.

## 8. Diğer konularla ilişkisi

- [Microservices Architecture](./01-microservices-architecture.md) — her microservice hexagonal yapıdadır
- [Domain-Driven Design](./02-domain-driven-design.md) — DDD ile hexagonal birlikte güç kazanır
- [Validation Strategy](../03-backend/04-validation-strategy.md) — adapter vs core validation
- [Error Handling RFC 7807](../03-backend/05-error-handling-rfc7807.md) — exception'lar adapter'da nasıl HTTP'ye dönüşür
- [gRPC Service Communication](../03-backend/03-grpc-service-communication.md) — gRPC bir adapter tipi
- [Outbox Pattern](./06-outbox-pattern.md) — `OutboxEventPublisher` outbound port örneği

## 9. Daha derine inmek için

**Orijinal kaynaklar:**
- Alistair Cockburn, "Hexagonal Architecture" (2005, orijinal makale): alistair.cockburn.us/hexagonal-architecture
- Tom Hombergs, "Get Your Hands Dirty on Clean Architecture" (2019) — Spring Boot örnekleriyle hexagonal
- Robert C. Martin, "Clean Architecture" (2017)
- Jeffrey Palermo, "Onion Architecture" serisi (2008)

**Spring + Hexagonal pratik:**
- Reflectoring blog (Tom Hombergs) — hexagonal Spring tutorials
- Baeldung — "Hexagonal Architecture in Java"
- Spring Modulith (modüler monolit için)

**Search keywords (İngilizce):**
- "hexagonal architecture java spring boot"
- "ports and adapters pattern"
- "onion architecture vs hexagonal"
- "clean architecture java example"
- "domain layer no framework dependency"
- "archunit hexagonal architecture test"

## 10. Sözlük

- **Adapter** — Hexagonal'de port'u somut implement eden sınıf. REST controller, JPA repository, Kafka consumer.
- **Anti-Corruption Layer** — Dış sistemden gelen modeli kendi diline tercüme katmanı. ACL aslında bir tür adapter.
- **Application Service** — Use case orchestration yapan, port'ları kullanan servis sınıfı.
- **Boundary** — Hexagonal'de iç (core) ile dış (infrastructure) arasındaki çizgi. Port'lar bu çizgiyi keser.
- **Driving Adapter / Driving Port** — Inbound (primary) adapter ve port. Sistemi tetikleyenler.
- **Driven Adapter / Driven Port** — Outbound (secondary) adapter ve port. Sistemin ihtiyaç duydukları.
- **Hexagonal Architecture** — Ports & Adapters pattern. Core'u dış dünyadan izole eder.
- **Inbound Port** — Use case interface. Dış dünyanın core'a giriş kapısı.
- **Outbound Port** — Core'un dış dünyaya ihtiyaç duyduğu interface (Repository, Publisher, Client).
- **Port** — Core ile adapter arasındaki interface sözleşme.
- **Use Case** — Bir iş kabiliyetinin sunucu tarafındaki ismi. Hexagonal'de inbound port == use case.
