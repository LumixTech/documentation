---
title: Validation Strategy
description: Lumix'in validation katmanlama stratejisi — adapter input validation (Jakarta Bean Validation) vs core domain invariant ayrımı.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

Bu sayfa Lumix'te **validation'ın nerede yapılacağı** kararının altında yatan mantığı anlatıyor. Hexagonal Architecture'da iki tip validation vardır: **adapter seviyesi input validation** ("Bu HTTP request'i şekil olarak doğru mu?") ve **domain seviyesi invariant check** ("Bu iş kuralı sağlanıyor mu?"). Sayfa **Jakarta Bean Validation** kullanımı, custom constraint yazımı, domain invariant tasarımı, gRPC input validation ve **validation'ın hangi katmanda hangi hata kodunu üretmesi** gerektiğini gösteriyor. Sonunda okuyan biri bir feature'a validation eklerken kuralı doğru katmana yerleştirebilmeli.

## 1. Bu nedir? (Sıfırdan)

**Validation** = bir verinin doğruluğunu/uygunluğunu kontrol etmek. İki ayrı kavramı çok karıştırılır:

1. **Input validation (şekil/format kontrolü):**
   - "Email field'ı dolu mu? @ içeriyor mu?"
   - "Date geçerli format mı?"
   - "Liste boş değil mi?"
   - "Sayı 0-100 arası mı?"

2. **Business rule validation / Invariant (iş kuralı):**
   - "Sınıf kapasitesi aşılıyor mu?"
   - "Yoklama 24 saat sonra revize edilebilir mi?"
   - "Refund tutarı orijinal payment'tan büyük olamaz"
   - "Kullanıcı bu tenant'a atanmamış"

Bu iki şey **farklı katmanlarda**, **farklı zamanlarda** kontrol edilir, **farklı hata kodları** üretir.

**Günlük hayattan analoji:**
Bir banka şubesine para çekmek için gidiyorsun.

- **Input validation = güvenlik girişi:**
  - "Üst kısımdaki kapı çalışıyor mu?"
  - "Üzerinizde silah var mı?"
  - "Geçerli kimlik var mı?"
  - Bu kontroller **şekilsel**, sen daha içeri girmeden olur.

- **Business validation = banka memuru:**
  - "Hesabınızda yeterli bakiye var mı?"
  - "Günlük çekim limitini aştınız mı?"
  - "Bu hesap dondurulmuş mu?"
  - Bu kontroller **iş kuralları**, memurun bilgisi gerektirir.

Güvenlik girişinde başarısız olursan içeri giremezsin — banka memurunu hiç görmezsin. Memur seviyesindeki kontroller daha derin, daha iş-spesifik.

### Lumix'in validation katmanlaması

```
                    ┌──────────────────────────────┐
                    │   HTTP Request gelir         │
                    └──────────────┬───────────────┘
                                   ▼
              ┌────────────────────────────────────────────┐
              │  KATMAN 1: Inbound Adapter Validation       │
              │  - Jakarta Bean Validation (@Valid, @NotNull)│
              │  - DTO şekil kontrolü                       │
              │  - Hata: HTTP 400 Bad Request               │
              └──────────────┬─────────────────────────────┘
                             │ DTO geçerli
                             ▼
              ┌────────────────────────────────────────────┐
              │  KATMAN 2: Use Case Pre-condition (light)   │
              │  - Domain'e ulaşmadan önceki çapraz kontrol │
              │  - Tipik: cross-aggregate kontrol           │
              │  - Hata: BusinessException → HTTP 409/422   │
              └──────────────┬─────────────────────────────┘
                             │ pre-conditions OK
                             ▼
              ┌────────────────────────────────────────────┐
              │  KATMAN 3: Domain Invariant Check (ana)     │
              │  - Aggregate root metodlarında              │
              │  - throw IllegalStateException veya         │
              │    domain-specific exception                │
              │  - Hata: BusinessException → HTTP 409/422   │
              └──────────────┬─────────────────────────────┘
                             │ invariant satisfied
                             ▼
              ┌────────────────────────────────────────────┐
              │  KATMAN 4: DB Constraint                     │
              │  - PK, FK, UNIQUE, CHECK constraint         │
              │  - Race condition'da son savunma            │
              │  - Hata: ConstraintViolation → 409 Conflict │
              └────────────────────────────────────────────┘
```

## 2. Hangi problemi çözüyor?

Validation katmanlaması olmadan tipik sorunlar:

**Acı 1 — Aynı kural birden çok yerde.**
"Email format kontrolü" controller'da, service'te, repository'de tekrar. Biri kaçırılır → bug.

**Acı 2 — Yanlış katmanda yanlış kural.**
"Sınıf kapasitesi" controller'da `@Max(30)` ile kontrol edilir. Ama bu domain kuralı, business mantığı — controller'ın görevi değil. Yarın sınıf kapasitesi tenant'a göre değişebilir → controller refactor.

**Acı 3 — Hata kodu kafa karıştırıcı.**
Domain rule violation `400 Bad Request` döner — client "input formatım yanlış" sanır. Aslında `409 Conflict` veya `422 Unprocessable Entity` olmalı.

**Acı 4 — Domain framework'e bağlanır.**
Aggregate içinde `@NotNull`, `@Size` annotation'ları. Validation framework değişirse domain kırılır.

**Acı 5 — Validation yokken DB'ye yazıyoruz.**
Pre-flight kontrol yok, INSERT atılıyor, DB constraint exception fırlatıyor. Stack trace karışık, hata mesajı kullanıcıya gösterilemez.

**Acı 6 — Race condition'da kontrol yetmiyor.**
"Sınıfa 30 öğrenci max" kontrolü uygulamada. İki kullanıcı aynı anda enroll basıyor, ikisi de 30 sınırı aşmadığını görüyor. Sonuç: 31 öğrenci.

Katmanlama bu acıları çözer:

| Acı | Katmanlama çözümü |
|---|---|
| Aynı kural birden çok yerde | Her kural ait olduğu katmanda — adapter'da yapısal, domain'de iş |
| Yanlış katmanda yanlış kural | Adapter = şekil, domain = iş — net görev paylaşımı |
| Hata kodu kafa karıştırıcı | 400 = input yanlış, 409/422 = iş kuralı ihlali |
| Domain framework'e bağlanır | Domain'de annotation yok, plain throw |
| Pre-flight yok | Domain check INSERT'ten önce |
| Race condition | DB constraint son savunma |

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Jakarta Bean Validation (adapter katmanı)

**Jakarta Bean Validation** (eskiden JSR-303 / Bean Validation), Java'nın standart annotation-based validation framework'ü. Hibernate Validator referans implementasyonu.

Spring Boot'ta `@Valid` ile entegre. Controller method parameter'ında `@Valid` → request body validation otomatik.

Temel annotation'lar:
- `@NotNull`, `@NotBlank`, `@NotEmpty`
- `@Size(min, max)`
- `@Min`, `@Max`, `@DecimalMin`, `@DecimalMax`
- `@Pattern(regexp)`
- `@Email`
- `@Past`, `@PastOrPresent`, `@Future`
- `@Positive`, `@PositiveOrZero`, `@Negative`, `@NegativeOrZero`
- `@Valid` (nested object'i de validate et)

Custom annotation yazılabilir.

### 3.2. Use Case Service Pre-condition

Bazı kontroller domain'e girmeden önce yapılır:
- "Bu kullanıcı bu tenant'a atanmış mı?" (cross-aggregate)
- "Bu class id gerçekten var mı?" (cross-service)

Burada `BusinessException` veya benzer custom exception fırlatılır.

```java
@Override
public AttendanceId execute(MarkAttendanceCommand cmd) {
    // Pre-condition: class organization'da var mı?
    ClassInfo classInfo = orgClient.getClass(cmd.classId().value())
        .orElseThrow(() -> new ClassNotFoundException(cmd.classId()));

    if (!classInfo.tenantId().equals(cmd.tenantId())) {
        throw new TenantMismatchException();
    }

    // Şimdi domain'e gir
    Attendance attendance = ...;
    attendance.mark(...);
    // ...
}
```

### 3.3. Domain Invariant Check (asıl validation)

Aggregate root metodları **her invariant'ı** her zaman doğru tutar. Bunun anlamı: bir aggregate'i hatalı state'e sokmak imkansız.

```java
public class Attendance {

    public void revise(StudentId studentId, PresenceStatus newPresence, Clock clock) {
        // Invariant 1: status check
        if (status != Status.SUBMITTED) {
            throw new IllegalStateException("Sadece SUBMITTED durumdaki yoklama revize edilebilir");
        }

        // Invariant 2: time window
        Duration elapsed = Duration.between(submittedAt, LocalDateTime.now(clock));
        if (elapsed.toHours() >= 24) {
            throw new AttendanceRevisionWindowExpiredException(id);
        }

        // Invariant 3: student in attendance
        StudentMark current = marks.stream()
            .filter(m -> m.studentId().equals(studentId))
            .findFirst()
            .orElseThrow(() -> new StudentNotInAttendanceException(studentId));

        // Apply
        marks.remove(current);
        marks.add(new StudentMark(studentId, newPresence));
        domainEvents.add(new AttendanceRevisedEvent(...));
    }
}
```

Bu metodlar **framework-bağımsız** — sadece JDK ve domain class'ları.

### 3.4. DB Constraint (son savunma)

PostgreSQL constraint'leri **race condition'ı** son anda yakalar:

```sql
CREATE TABLE attendances (
    id UUID PRIMARY KEY,
    class_id UUID NOT NULL,
    attendance_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED')),
    tenant_id UUID NOT NULL,
    UNIQUE (class_id, attendance_date)
);
```

`UNIQUE (class_id, attendance_date)` constraint: iki kullanıcı aynı anda aynı sınıf+tarih için INSERT atarsa — biri başarılı, diğeri `ConstraintViolationException`.

Lumix'te DB constraint **net business kuralları** için:
- UNIQUE constraints
- NOT NULL constraints
- CHECK constraints (enum value range)
- FOREIGN KEY (cross-table referential integrity)

Detay business kontrol DB değil domain'de.

### 3.5. Hata kodu eşlemesi

| Validation seviyesi | Hata türü | HTTP code | gRPC status |
|---|---|---|---|
| Adapter (input) | `MethodArgumentNotValidException` | **400 Bad Request** | `INVALID_ARGUMENT` |
| Use case pre-condition | `BusinessException`/`NotFoundException` | **404** veya **409** | `NOT_FOUND` / `FAILED_PRECONDITION` |
| Domain invariant | `IllegalStateException`/custom | **409 Conflict** veya **422** | `FAILED_PRECONDITION` |
| DB constraint | `DataIntegrityViolationException` | **409 Conflict** | `ALREADY_EXISTS` / `FAILED_PRECONDITION` |

Detay: [Error Handling RFC 7807](./05-error-handling-rfc7807.md).

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Lumix katmanlama kuralı

**Kural 1:** Adapter (REST controller, gRPC service, Kafka consumer) **input validation** yapar. Şekilsel kontrol — null, format, range, regex.

**Kural 2:** Use case service **cross-aggregate pre-condition** yapar. Diğer servisten/aggregate'ten veri kontrolü.

**Kural 3:** Aggregate root metodları **iş kurallarını (invariant)** korur. Her metod aggregate'i geçerli state'te tutar.

**Kural 4:** DB constraint **son savunma**. Race condition'ı yakalar.

**Kural 5:** Aynı kural **birden fazla katmanda olmaz** (defense-in-depth istisnası: DB constraint + domain check).

### 4.2. Annotation kullanım kuralları

- **Adapter DTO'lar:** Jakarta Bean Validation annotation'larıyla doldurulur.
- **Domain class'lar:** Validation annotation YOK. Constructor/method içinde explicit check.
- **JPA Entity (Lumix pragmatik: domain class JPA annotated):** `@NotNull`, `@Size` JPA için, ama business kontrol değil sadece DB schema tanımı.

### 4.3. Validation grupları (advanced)

Aynı DTO farklı senaryolarda farklı kurallar:

```java
public interface CreateGroup {}
public interface UpdateGroup {}

public record StudentRequest(
    @NotNull(groups = UpdateGroup.class) UUID id,
    @NotBlank(groups = {CreateGroup.class, UpdateGroup.class}) String name,
    @NotNull(groups = CreateGroup.class) LocalDate birthDate
) {}

@PostMapping
public ... create(@Validated(CreateGroup.class) @RequestBody StudentRequest req) { ... }

@PutMapping("/{id}")
public ... update(@Validated(UpdateGroup.class) @RequestBody StudentRequest req) { ... }
```

### 4.4. Cross-field validation

Bazı kurallar tek field'a değil, birkaç field'ın ilişkisine bağlıdır:

```java
public record DateRangeRequest(
    @NotNull LocalDate startDate,
    @NotNull LocalDate endDate
) {
    @AssertTrue(message = "endDate, startDate'ten önce olamaz")
    public boolean isDateRangeValid() {
        return endDate == null || startDate == null || !endDate.isBefore(startDate);
    }
}
```

Ya da custom annotation:

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DateRangeValidator.class)
public @interface ValidDateRange {
    String message() default "Geçersiz tarih aralığı";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

public class DateRangeValidator implements ConstraintValidator<ValidDateRange, DateRangeRequest> {
    @Override
    public boolean isValid(DateRangeRequest req, ConstraintValidatorContext ctx) {
        return req.endDate() == null || req.startDate() == null
            || !req.endDate().isBefore(req.startDate());
    }
}

@ValidDateRange
public record DateRangeRequest(LocalDate startDate, LocalDate endDate) {}
```

### 4.5. Custom domain exception hierarchy

Lumix'te exception sınıfları:

```java
// domain/exception/DomainException.java
public abstract class DomainException extends RuntimeException {
    protected DomainException(String message) { super(message); }
    protected DomainException(String message, Throwable cause) { super(message, cause); }
}

// Sub-types
public class BusinessRuleViolationException extends DomainException { ... }
public class NotFoundException extends DomainException { ... }
public class DuplicateException extends DomainException { ... }
public class PermissionDeniedException extends DomainException { ... }
public class TenantMismatchException extends DomainException { ... }

// Spesifik
public class AttendanceRevisionWindowExpiredException extends BusinessRuleViolationException {
    public AttendanceRevisionWindowExpiredException(AttendanceId id) {
        super("Yoklama 24 saat içinde revize edilebilir: " + id);
    }
}

public class ClassNotFoundException extends NotFoundException {
    public ClassNotFoundException(ClassId id) {
        super("Class bulunamadı: " + id);
    }
}
```

Global `@ControllerAdvice` bu exception'ları HTTP code'lara çevirir.

### 4.6. gRPC validation

gRPC request'ler de validate edilir, ama Spring `@Valid` direkt çalışmaz. Manuel:

```java
@Override
public void markAttendance(MarkAttendanceRequest req, StreamObserver<...> obs) {
    // Manuel input validation
    if (req.getClassId().isEmpty()) {
        obs.onError(Status.INVALID_ARGUMENT
            .withDescription("class_id boş olamaz")
            .asRuntimeException());
        return;
    }
    try {
        UUID classId = UUID.fromString(req.getClassId());
    } catch (IllegalArgumentException ex) {
        obs.onError(Status.INVALID_ARGUMENT
            .withDescription("class_id geçersiz UUID")
            .asRuntimeException());
        return;
    }

    // ... use case çağrısı
}
```

Veya **protoc-gen-validate** (PGV) — proto'da validation kuralları:

```proto
import "validate/validate.proto";

message MarkAttendanceRequest {
  string class_id = 1 [(validate.rules).string.uuid = true];
  string date = 2 [(validate.rules).string.pattern = "^\\d{4}-\\d{2}-\\d{2}$"];
  repeated StudentMark marks = 3 [(validate.rules).repeated.min_items = 1];
}
```

Build time'da Java validator gen. Şimdilik Lumix manuel validation, gerekirse PGV'e geçer.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

**Alternatif 1 — Tüm validation domain'de.**
Annotation kullanmadan tüm kontrol domain'de.

Niye elendi:
- Adapter'da gelen request'in formatını domain'in bilmesi tuhaf
- Boilerplate patlar
- Spring'in standart annotation'ları kullanılamaz
- OpenAPI doc otomatik üretimi (annotation'lardan) imkansız

**Alternatif 2 — Tüm validation adapter'da.**
İş kurallarını da `@Min`, `@Max` ile DTO'da.

Niye elendi:
- İş kuralı = domain, adapter'a sığmaz
- Yarın aynı kural başka adapter'da (gRPC) tekrar yazılır
- Test edilebilirlik düşük

**Alternatif 3 — Tek bir validation framework (örn. Spring Validator).**
Spring-spesifik validation interface.

Niye elendi:
- Jakarta Bean Validation daha standart, geniş ekosistem
- Spring Validator bytecode'lu, opinionated

**Alternatif 4 — Schema'dan otomatik validation.**
JSON Schema veya protobuf-validate ile %100 declarative.

Niye elendi:
- Domain invariant'lar declarative ifade edilemez
- Karmaşık iş kuralları kod gerektirir
- Sadece input validation için uygundur

### 5.2. Kabul edilen trade-off'lar

| Trade-off | Maliyet | Mitigation |
|---|---|---|
| İki ayrı framework (Jakarta + domain code) | Geliştirici hangisi nerede sorusu | Net kural + doc |
| Custom exception sınıfları | Sınıf sayısı artar | Domain'in ifadesi açık |
| Validation grupları karmaşıklığı | Annotation grupları confusing | Yeni geliştiriciye eğitim |
| Aynı kural iki kez | Defense-in-depth: domain + DB | Bilinçli tekrar |

## 6. Pratik örnek

### 6.1. Tam akış — yoklama oluşturma

**REST DTO (adapter input validation):**

```java
// adapter/in/rest/dto/MarkAttendanceRequest.java
public record MarkAttendanceRequest(
    @NotNull(message = "class_id zorunlu") UUID classId,
    @NotNull @PastOrPresent(message = "Tarih bugünden ileri olamaz") LocalDate date,
    @NotEmpty(message = "En az bir öğrenci işaretlenmeli")
    @Size(max = 50, message = "Tek seferde 50'den fazla öğrenci işaretlenemez")
    @Valid
    List<StudentMarkRequest> marks
) {
    public record StudentMarkRequest(
        @NotNull UUID studentId,
        @NotNull PresenceStatus presence
    ) {}
}
```

**Controller:**

```java
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
        // @Valid başarısız olursa Spring zaten exception fırlatır
        MarkAttendanceCommand cmd = AttendanceRestMapper.toCommand(req, user.tenantId());
        AttendanceId id = markAttendanceUseCase.execute(cmd);
        return ResponseEntity.created(URI.create("/api/v1/attendance/" + id.value()))
            .body(new AttendanceResponse(id.value()));
    }
}
```

**Use case service (pre-condition + orchestration):**

```java
@Service
@Transactional
@RequiredArgsConstructor
public class MarkAttendanceService implements MarkAttendanceUseCase {

    private final AttendanceRepository attendanceRepo;
    private final OrganizationClient orgClient;
    private final OutboxEventPublisher outbox;
    private final Clock clock;

    @Override
    public AttendanceId execute(MarkAttendanceCommand cmd) {
        // Pre-condition 1: class organization'da var mı?
        ClassInfo classInfo = orgClient.getClass(cmd.classId().value());

        // Pre-condition 2: tenant match
        if (!classInfo.tenantId().equals(cmd.tenantId())) {
            throw new TenantMismatchException();
        }

        // Pre-condition 3: class'a atanmış öğrenciler mi?
        Set<StudentId> classStudents = classInfo.enrolledStudentIds();
        for (var mark : cmd.marks()) {
            if (!classStudents.contains(mark.studentId())) {
                throw new StudentNotEnrolledException(mark.studentId(), cmd.classId());
            }
        }

        // Domain'e geçiş
        Attendance attendance = attendanceRepo
            .findByClassAndDate(cmd.classId(), cmd.date())
            .orElseGet(() -> Attendance.create(cmd.classId(), cmd.date(), cmd.tenantId()));

        for (var mark : cmd.marks()) {
            attendance.mark(mark.studentId(), mark.presence());  // INVARIANT check
        }
        attendance.submit(clock);  // INVARIANT check

        attendanceRepo.save(attendance);
        attendance.domainEvents().forEach(outbox::publish);
        attendance.clearDomainEvents();

        return attendance.id();
    }
}
```

**Domain aggregate (invariant'lar):**

```java
public class Attendance {

    public void mark(StudentId studentId, PresenceStatus presence) {
        // INVARIANT 1: status
        if (status != Status.DRAFT) {
            throw new AttendanceAlreadySubmittedException(id);
        }

        // INVARIANT 2: duplicate
        if (marks.stream().anyMatch(m -> m.studentId().equals(studentId))) {
            throw new DuplicateStudentMarkException(studentId);
        }

        marks.add(new StudentMark(studentId, presence));
    }

    public void submit(Clock clock) {
        // INVARIANT 1: status
        if (status != Status.DRAFT) {
            throw new IllegalStateException("Sadece DRAFT submit edilebilir");
        }

        // INVARIANT 2: non-empty
        if (marks.isEmpty()) {
            throw new EmptyAttendanceException();
        }

        this.status = Status.SUBMITTED;
        this.submittedAt = LocalDateTime.now(clock);
        domainEvents.add(new AttendanceMarkedEvent(...));
    }
}
```

**DB constraint (son savunma):**

```sql
-- Flyway migration
CREATE TABLE attendances (
    id UUID PRIMARY KEY,
    class_id UUID NOT NULL,
    attendance_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    tenant_id UUID NOT NULL,
    submitted_at TIMESTAMPTZ,
    UNIQUE (class_id, attendance_date),  -- aynı sınıf + tarih bir kez
    CONSTRAINT chk_status CHECK (status IN ('DRAFT', 'SUBMITTED'))
);
```

### 6.2. Custom constraint annotation

```java
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TenantOwnedValidator.class)
public @interface TenantOwned {
    String message() default "Resource bu tenant'a ait değil";
    Class<? extends Payload>[] payload() default {};
    Class<?>[] groups() default {};
    String resource();
}

@Component
@RequiredArgsConstructor
public class TenantOwnedValidator implements ConstraintValidator<TenantOwned, UUID> {

    private final TenantOwnershipChecker checker;
    private String resource;

    @Override
    public void initialize(TenantOwned annotation) {
        this.resource = annotation.resource();
    }

    @Override
    public boolean isValid(UUID resourceId, ConstraintValidatorContext ctx) {
        if (resourceId == null) return true;
        UUID currentTenant = TenantContext.current();
        return checker.belongsToTenant(resource, resourceId, currentTenant);
    }
}

// Kullanım
public record SetGradeRequest(
    @TenantOwned(resource = "student") UUID studentId,
    @Min(0) @Max(100) int grade
) {}
```

### 6.3. Programmatic validation (Validator API)

```java
@Service
@RequiredArgsConstructor
public class BulkImportService {

    private final Validator validator;

    public ImportResult importStudents(List<StudentImportDto> rows) {
        List<ImportError> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            StudentImportDto row = rows.get(i);
            Set<ConstraintViolation<StudentImportDto>> violations = validator.validate(row);
            if (!violations.isEmpty()) {
                errors.add(new ImportError(i, violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .toList()));
                continue;
            }
            saveStudent(row);
        }
        return new ImportResult(rows.size() - errors.size(), errors);
    }
}
```

### 6.4. Test

**Adapter validation test:**

```java
@WebMvcTest(AttendanceController.class)
class AttendanceControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean MarkAttendanceUseCase useCase;

    @Test
    void shouldReturn400WhenClassIdMissing() throws Exception {
        String body = """
            {
              "date": "2026-05-27",
              "marks": [{"studentId": "...", "presence": "PRESENT"}]
            }
            """;
        mockMvc.perform(post("/api/v1/attendance")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("classId"));
    }
}
```

**Domain invariant test:**

```java
class AttendanceTest {

    @Test
    void shouldThrowWhenSubmittingEmpty() {
        Attendance a = Attendance.create(ClassId.of("c1"), LocalDate.now(), UUID.randomUUID());
        assertThatThrownBy(() -> a.submit(Clock.systemUTC()))
            .isInstanceOf(EmptyAttendanceException.class);
    }

    @Test
    void shouldThrowWhenMarkingAfterSubmit() {
        Attendance a = Attendance.create(ClassId.of("c1"), LocalDate.now(), UUID.randomUUID());
        a.mark(StudentId.of("s1"), PresenceStatus.PRESENT);
        a.submit(Clock.systemUTC());

        assertThatThrownBy(() -> a.mark(StudentId.of("s2"), PresenceStatus.PRESENT))
            .isInstanceOf(AttendanceAlreadySubmittedException.class);
    }
}
```

## 7. Dikkat edilecek tuzaklar

**Tuzak 1 — Domain'de `@NotNull` kullanmak.**
Aggregate'de Bean Validation annotation = framework lock-in + business kural değil.
**Önleme:** Domain'de constructor/method başında explicit check (`Objects.requireNonNull`, `if (...) throw`).

**Tuzak 2 — Controller'da iş kuralı check'i.**
"Sınıf kapasitesi 30" controller'da `@Max(30)` — domain'e ait.
**Önleme:** Adapter'da sadece şekil. İş kuralı aggregate'te.

**Tuzak 3 — `@Valid` unutmak.**
`@RequestBody MarkAttendanceRequest req` — `@Valid` yok, validation çalışmıyor.
**Önleme:** `@RequestBody @Valid` her yerde. Code review checklist.

**Tuzak 4 — Validation message'ları kullanıcıya direkt göstermek.**
Hata mesajı `"birthDate must not be null"` — kullanıcı İngilizce, technical.
**Önleme:** `messages.properties` ile i18n. Veya RFC 7807 problem details ile structured.

**Tuzak 5 — Pre-condition'da çok iş yapmak.**
Use case service'te 200 satır validation. Asıl iş minimal.
**Önleme:** Pre-condition mininum. Asıl invariant aggregate'te.

**Tuzak 6 — DB constraint'i atlamak.**
Sadece application-level validation. Race condition'da duplicate INSERT geçiyor.
**Önleme:** Critical invariant'lar için DB constraint (UNIQUE, CHECK).

**Tuzak 7 — Custom annotation overkill.**
Her business rule için custom annotation. Maintenance kabusu.
**Önleme:** Custom annotation sadece **tekrar eden** ve **gerçekten declarative** kontroller için. Karmaşık iş kuralı = domain method.

**Tuzak 8 — Exception swallowing.**
Validation hatası catch edilir, generic 500 dönülür.
**Önleme:** Validation exception'ları net hata kodlarına ile map et.

**Tuzak 9 — Cross-tenant data exposure.**
Kullanıcı başkasının tenant'ındaki resource'a referans verir, validation bunu yakalayamaz.
**Önleme:** Tenant ownership check pre-condition'da. Veya `@TenantOwned` custom constraint.

**Tuzak 10 — Validation grupları karmaşıklığı.**
3-4 grup arasında karışıklık, hangi action hangi grup belirsiz.
**Önleme:** Mümkünse ayrı DTO'lar. Grup sadece açık kazanım sağlıyorsa.

**Tuzak 11 — gRPC validation atlamak.**
REST'te `@Valid` ama gRPC'de manuel kontrol yok. gRPC çağrıları validate edilmiyor.
**Önleme:** gRPC service başında manuel check veya protoc-gen-validate.

**Tuzak 12 — DB constraint hata mesajını user'a göstermek.**
`ERROR: duplicate key value violates unique constraint "attendances_class_id_attendance_date_key"` — kullanıcı bunu anlamaz.
**Önleme:** Exception handler bu mesajı yakalayıp friendly mesaja çevirir.

## 8. Diğer konularla ilişkisi

- [Hexagonal Architecture](../02-architecture-patterns/03-hexagonal-architecture.md) — validation katmanları hexagonal yapıya denk
- [Domain-Driven Design](../02-architecture-patterns/02-domain-driven-design.md) — invariant aggregate'in temel kavramı
- [Error Handling RFC 7807](./05-error-handling-rfc7807.md) — validation hatalarının HTTP'ye çevrilmesi
- [gRPC Service Communication](./03-grpc-service-communication.md) — gRPC input validation
- [Spring Boot Foundation](./01-spring-boot-foundation.md) — `@Valid` Spring entegrasyonu

## 9. Daha derine inmek için

**Resmi:**
- jakarta.ee/specifications/bean-validation/3.0
- hibernate.org/validator
- docs.spring.io/spring-framework/reference/core/validation.html

**Kitap:**
- "Bean Validation Specification" — Jakarta EE
- "Implementing Domain-Driven Design" — Vaughn Vernon (invariant kısmı)

**Search keywords:**
- "jakarta bean validation spring boot"
- "hibernate validator custom constraint"
- "domain invariant validation ddd"
- "grpc input validation protoc-gen-validate"
- "spring boot validation groups"
- "method argument not valid exception handler"

## 10. Sözlük

- **Aggregate Invariant** — Aggregate'in her zaman doğru olması gereken iş kuralı.
- **Bean Validation** — Jakarta EE standart annotation-based validation framework (JSR-380).
- **Constraint** — Validation kuralı (annotation).
- **ConstraintValidator** — Custom annotation'ın validation logic'i.
- **DB Constraint** — Veritabanı seviyesi kural (UNIQUE, CHECK, NOT NULL, FK).
- **Domain Exception** — Business rule ihlali için fırlatılan exception.
- **Hibernate Validator** — Bean Validation referans implementasyonu.
- **Input Validation** — Adapter seviyesinde şekil/format kontrolü.
- **Invariant** — Aggregate root'un sürekli koruduğu doğruluk kuralı.
- **Jakarta Bean Validation** — `javax.validation` → `jakarta.validation` rebranding.
- **Pre-condition** — Use case başında kontrol edilen ön koşul.
- **Validation Group** — Aynı DTO'nun farklı senaryolarda farklı kurallarla validate edilmesi.
