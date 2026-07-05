---
title: Error Handling — RFC 7807 Problem Details
description: RFC 7807 Problem Details for HTTP APIs, Spring @ControllerAdvice ile hata yönetimi, exception hierarchy, gRPC error mapping ve frontend tüketimi.
sidebar_position: 5
---

## Bu sayfa ne anlatıyor?

Bu sayfa Lumix backend'inin **standart hata cevap formatı** olan **RFC 7807 Problem Details for HTTP APIs**'i anlatıyor: bu standart ne sunar, JSON nasıl şekillenir, **Spring `@ControllerAdvice`** ile nasıl uygulanır, **Lumix exception hierarchy**'si nasıl tasarlandı, **gRPC** hatalarının REST cevabına nasıl çevrildiği ve frontend'in bu hataları nasıl tüketmesi gerektiği. Sayfanın sonunda backend geliştirici yeni bir exception fırlatmak için doğru tipi seçebilmeli ve bunun client tarafında nasıl gözükeceğini bilmeli.

## 1. Bu nedir? (Sıfırdan)

### RFC 7807 — Problem Details for HTTP APIs

**RFC 7807**, IETF tarafından 2016'da yayınlanan, HTTP API'lar için **standartlaştırılmış hata cevap formatı**dır. (2023'te RFC 9457 ile güncellendi ama Lumix referansta hala "7807" diyoruz çünkü orijinal isim böyle yerleşmiş.)

Amaç: REST API'lerinde herkes kendi hata formatını uydurmasın. Standart bir yapı olsun.

**Klasik (RFC 7807 öncesi) API hata cevapları:**

```json
// Bir API
{ "error": "Not found", "code": 404 }

// Başka bir API
{ "status": "error", "message": "Resource missing" }

// Başka bir API
{ "errorCode": "RES_001", "details": [...] }

// Başka bir API
{ "success": false, "msg": "Hata oluştu" }
```

Her API farklı. Client her API için ayrı parser yazar.

**RFC 7807 önerisi:** tek bir JSON yapısı, `application/problem+json` content type.

```json
{
  "type": "https://lumix.io/problems/attendance-revision-window-expired",
  "title": "Attendance Revision Window Expired",
  "status": 409,
  "detail": "Yoklama 24 saat içinde revize edilebilir; bu yoklama 48 saat önce alındı.",
  "instance": "/api/v1/attendance/01J5MP3K5N1XYZ",
  "correlationId": "8c7e2a1f-3d4b-4e5c-9a8f-1b2c3d4e5f6a"
}
```

**Standart field'lar:**
- **`type`** (URI): hatanın tipini tanımlayan URI. Genelde human-readable docs sayfasını gösterir.
- **`title`** (string): kısa, human-readable özet. Aynı `type` için sabit kalır.
- **`status`** (int): HTTP status code (cevabın status'üyle aynı).
- **`detail`** (string): bu spesifik hatanın detayı. Değişebilir.
- **`instance`** (URI): hatanın oluştuğu spesifik instance (URL).

**Custom field'lar** eklenebilir: Lumix'te `correlationId`, `errors[]` (validation hataları için), `field` vs.

### Günlük hayattan analoji

Bir restoranda sipariş verdin, hata oldu:

- **RFC 7807 öncesi:** Garson "olmadı işte" der ve uzaklaşır. Niye? Stoktan mı bitti? Mutfak çalışmıyor mu? Senin sipariş hatalı mıydı? Belirsiz.

- **RFC 7807 ile:** Garson belirli formatta cevap verir:
  - "Olay tipi: stok yok"
  - "Detay: somon balığı bugün gelmedi"
  - "Sipariş no: 12345"
  - "Ne yapmalısın: başka ürün seç"

Yapı aynı her hatada — sen "siparişle hata varsa ne yapacağımı" biliyorsun.

## 2. Hangi problemi çözüyor?

Standart hata formatı olmadan tipik sorunlar:

**Acı 1 — Frontend her endpoint için ayrı hata parser yazar.**
"Login endpoint hata field'ı `message`, register endpoint `error`, profile endpoint `errors[]`." Frontend kodu hata mantığıyla şişer.

**Acı 2 — Hata kategorize edilemez.**
Backend "User not found" mesajı dönüyor. Frontend bu hatayı 404 ile mi 422 ile mi göstereceğini bilemiyor.

**Acı 3 — Hata mesajı kullanıcıya gösterilebilir mi belirsiz.**
Backend `"NullPointerException at line 123"` dönüyor. Frontend bunu kullanıcıya gösteremez ama bilemez.

**Acı 4 — i18n nasıl yapılır?**
Backend Türkçe mesaj döner. Mobil uygulama İngilizce kullanıcıya nasıl gösterir? Veya tersi.

**Acı 5 — Aynı hata için tutarsız status code.**
Bir endpoint 404 dönerken başka biri 400 dönüyor (aynı "bulunamadı" durumu için).

**Acı 6 — Validation hataları detaylı değil.**
"Bad request" denilir, hangi field'da hata olduğu söylenmez.

**Acı 7 — Stack trace sızıntısı.**
Backend exception'ı detaylı log'lar, ama response'a da koyar. Üretimde stack trace client'a gider — bilgi sızıntısı.

**Acı 8 — Cross-service consistency yok.**
12 microservice, 12 farklı hata formatı. Frontend her servis için ayrı parsing.

RFC 7807 + tutarlı uygulama bu acıları çözer:

| Acı | Çözüm |
|---|---|
| Ayrı hata parser her endpoint | Tek standart yapı |
| Hata kategorize edilemez | `type` URI + `status` net |
| Mesaj gösterilebilir mi | `title` = generic (gösterilebilir), `detail` = teknik |
| i18n | `type` URI sabit, mesaj i18n key olarak |
| Tutarsız status code | `type` ile status sabit eşleşme |
| Validation detayı yok | Custom `errors[]` field'ı |
| Stack trace sızıntısı | Asla response'a koyma, sadece log'a |
| Cross-service tutarsızlık | Tüm servislerde aynı format |

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Problem Details JSON yapısı

Standart minimal:

```json
{
  "type": "https://lumix.io/problems/class-capacity-exceeded",
  "title": "Class Capacity Exceeded",
  "status": 409,
  "detail": "Sınıf 11-A'nın kapasitesi 30; 31. öğrenci eklenemez.",
  "instance": "/api/v1/classes/01J5MP3K5N1XYZ/enrollments"
}
```

Validation hatası (custom extension):

```json
{
  "type": "https://lumix.io/problems/validation-failed",
  "title": "Validation Failed",
  "status": 400,
  "detail": "Bir veya daha fazla field hatalı",
  "instance": "/api/v1/attendance",
  "correlationId": "8c7e2a1f-...",
  "errors": [
    { "field": "classId", "code": "NotNull", "message": "class_id zorunlu" },
    { "field": "marks[0].studentId", "code": "NotNull", "message": "studentId zorunlu" },
    { "field": "marks", "code": "Size", "message": "En fazla 50 öğrenci" }
  ]
}
```

### 3.2. HTTP status code seçimi

| Status | Anlam | Lumix'te tipik kullanım |
|---|---|---|
| **400 Bad Request** | Input şekil hatası | Bean Validation fail, JSON parse fail |
| **401 Unauthorized** | Auth yok / geçersiz token | Token eksik veya invalid |
| **403 Forbidden** | Auth var ama yetki yok | `@PreAuthorize` fail |
| **404 Not Found** | Resource yok | findById null |
| **405 Method Not Allowed** | HTTP method desteklenmiyor | Spring otomatik |
| **409 Conflict** | State ile çakışma | Duplicate, business rule ihlali |
| **410 Gone** | Resource kalıcı olarak silindi | Anonymized data |
| **422 Unprocessable Entity** | İş kuralı ihlali (semantic) | Domain invariant fail |
| **429 Too Many Requests** | Rate limit aşıldı | Kong + app rate limit |
| **500 Internal Server Error** | Beklenmeyen hata | Bug, NPE, unhandled exception |
| **502 Bad Gateway** | Upstream servis hatası | gRPC dependency çöktü |
| **503 Service Unavailable** | Geçici unavailable | DB, Kafka down |
| **504 Gateway Timeout** | Upstream timeout | gRPC deadline exceeded |

> **409 vs 422 ayrımı:** Endüstride kafa karışıklığı vardır. Lumix kuralı:
> - **409 Conflict** — duplicate, optimistic locking conflict, idempotency conflict
> - **422 Unprocessable Entity** — input şekilsel doğru ama iş kuralı reddediyor (örn. "yoklama 24 saat sonra revize edilemez")

### 3.3. Exception → HTTP mapping

Spring `@ControllerAdvice` ile global exception handler:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(...) { ... }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(...) { ... }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<ProblemDetail> handleBusinessRule(...) { ... }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(...) { ... }
}
```

Spring 6 (Boot 3.x) `ProblemDetail` sınıfını standart olarak destekler.

### 3.4. gRPC → REST hata çevirme

Akış:
- academic-service → organization-service gRPC çağrısı
- organization-service `Status.NOT_FOUND` döner
- academic-service'in client kodu bunu `ClassNotFoundException` yapar
- Bu exception academic-service controller'ında 404 + ProblemDetail olur

Yani: gRPC status code → custom exception → HTTP status.

### 3.5. Frontend tüketimi

Frontend (React + RTK Query) standart yapıyı bilir:

```typescript
// shared/api/baseQuery.ts
const baseQuery = fetchBaseQuery({
  baseUrl: '/api/v1',
  // ...
});

const baseQueryWithErrorHandling = async (args, api, extraOptions) => {
  const result = await baseQuery(args, api, extraOptions);
  if (result.error) {
    const problem = result.error.data as ProblemDetail;
    if (problem.status === 400 && problem.errors) {
      // Field-level validation hatası
      api.dispatch(showFormErrors(problem.errors));
    } else if (problem.status === 401) {
      api.dispatch(redirectToLogin());
    } else {
      api.dispatch(showErrorToast(problem.title));
    }
  }
  return result;
};
```

Frontend `type` URI'a bakıp özel davranış gösterebilir:
```typescript
if (problem.type.endsWith('attendance-revision-window-expired')) {
  showSpecificMessage('Yoklama revize süresi doldu...');
}
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Lumix exception hierarchy

```
RuntimeException (JDK)
  └── DomainException (abstract, com.lumix.common.exception)
        ├── BusinessRuleViolationException
        │     └── AttendanceRevisionWindowExpiredException
        │     └── ClassCapacityExceededException
        │     └── PaymentAlreadyCapturedException
        ├── NotFoundException
        │     └── ClassNotFoundException
        │     └── StudentNotFoundException
        │     └── InvoiceNotFoundException
        ├── DuplicateException
        │     └── DuplicateStudentMarkException
        ├── PermissionDeniedException
        │     └── ScopeViolationException
        ├── TenantMismatchException
        ├── ConflictException
        │     └── OptimisticLockException
        └── UnauthenticatedException
```

Her exception'ın bir `type` URI'ı ve default HTTP status'ü var.

### 4.2. Standart ProblemDetail format'ı

Lumix tüm servislerde aynı extension'lar:

```json
{
  "type": "https://lumix.io/problems/<problem-code>",
  "title": "<English title>",
  "status": <http-status>,
  "detail": "<context-specific message>",
  "instance": "<request URI>",
  "correlationId": "<UUID>",
  "tenantId": "<UUID>",
  "timestamp": "2026-05-27T10:30:00Z",
  "errors": [...]  // optional, validation için
}
```

### 4.3. Global exception handler (Spring)

```java
// adapter/in/rest/GlobalExceptionHandler.java
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private static final String PROBLEM_BASE_URI = "https://lumix.io/problems/";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
        MethodArgumentNotValidException ex, HttpServletRequest request
    ) {
        List<FieldErrorDetail> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new FieldErrorDetail(
                fe.getField(),
                fe.getCode(),
                fe.getDefaultMessage()
            ))
            .toList();

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Bir veya daha fazla field hatalı"
        );
        pd.setType(URI.create(PROBLEM_BASE_URI + "validation-failed"));
        pd.setTitle("Validation Failed");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("correlationId", MDC.get("correlation-id"));
        pd.setProperty("tenantId", MDC.get("tenant-id"));
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(
        NotFoundException ex, HttpServletRequest request
    ) {
        ProblemDetail pd = build(HttpStatus.NOT_FOUND, ex, request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<ProblemDetail> handleBusinessRule(
        BusinessRuleViolationException ex, HttpServletRequest request
    ) {
        ProblemDetail pd = build(HttpStatus.UNPROCESSABLE_ENTITY, ex, request);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    @ExceptionHandler(DuplicateException.class)
    public ResponseEntity<ProblemDetail> handleDuplicate(
        DuplicateException ex, HttpServletRequest request
    ) {
        ProblemDetail pd = build(HttpStatus.CONFLICT, ex, request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ProblemDetail> handlePermission(
        PermissionDeniedException ex, HttpServletRequest request
    ) {
        ProblemDetail pd = build(HttpStatus.FORBIDDEN, ex, request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ProblemDetail> handleUnauthenticated(
        UnauthenticatedException ex, HttpServletRequest request
    ) {
        ProblemDetail pd = build(HttpStatus.UNAUTHORIZED, ex, request);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(
        DataIntegrityViolationException ex, HttpServletRequest request
    ) {
        log.warn("DB integrity violation", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "Veri tutarlılığı ihlali"
        );
        pd.setType(URI.create(PROBLEM_BASE_URI + "data-conflict"));
        pd.setTitle("Data Conflict");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("correlationId", MDC.get("correlation-id"));
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
        Exception ex, HttpServletRequest request
    ) {
        log.error("Unhandled exception", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Beklenmeyen bir hata oluştu. correlationId ile destek ekibine başvurabilirsiniz."
        );
        pd.setType(URI.create(PROBLEM_BASE_URI + "internal-error"));
        pd.setTitle("Internal Server Error");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("correlationId", MDC.get("correlation-id"));
        pd.setProperty("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    private ProblemDetail build(HttpStatus status, DomainException ex, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        pd.setType(URI.create(PROBLEM_BASE_URI + ex.problemCode()));
        pd.setTitle(ex.title());
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("correlationId", MDC.get("correlation-id"));
        pd.setProperty("tenantId", MDC.get("tenant-id"));
        pd.setProperty("timestamp", Instant.now().toString());
        if (ex.context() != null) {
            ex.context().forEach(pd::setProperty);
        }
        return pd;
    }
}
```

### 4.4. DomainException base class

```java
// common/exception/DomainException.java
public abstract class DomainException extends RuntimeException {

    private final String problemCode;
    private final String title;
    private final Map<String, Object> context;

    protected DomainException(String problemCode, String title, String detail) {
        super(detail);
        this.problemCode = problemCode;
        this.title = title;
        this.context = new HashMap<>();
    }

    protected DomainException(String problemCode, String title, String detail, Map<String, Object> context) {
        super(detail);
        this.problemCode = problemCode;
        this.title = title;
        this.context = new HashMap<>(context);
    }

    public String problemCode() { return problemCode; }
    public String title() { return title; }
    public Map<String, Object> context() { return Collections.unmodifiableMap(context); }
}

// common/exception/BusinessRuleViolationException.java
public class BusinessRuleViolationException extends DomainException {
    public BusinessRuleViolationException(String code, String title, String detail) {
        super(code, title, detail);
    }
    public BusinessRuleViolationException(String code, String title, String detail, Map<String, Object> ctx) {
        super(code, title, detail, ctx);
    }
}

// academic/domain/exception/AttendanceRevisionWindowExpiredException.java
public class AttendanceRevisionWindowExpiredException extends BusinessRuleViolationException {
    public AttendanceRevisionWindowExpiredException(AttendanceId id, Duration elapsed) {
        super(
            "attendance-revision-window-expired",
            "Attendance Revision Window Expired",
            "Yoklama 24 saat içinde revize edilebilir; bu yoklama "
                + elapsed.toHours() + " saat önce alındı.",
            Map.of(
                "attendanceId", id.value().toString(),
                "elapsedHours", elapsed.toHours()
            )
        );
    }
}
```

### 4.5. gRPC → DomainException mapping (client side)

```java
@Component
@RequiredArgsConstructor
public class GrpcOrganizationClient implements OrganizationClient {

    @GrpcClient("organization-service")
    private OrganizationServiceGrpc.OrganizationServiceBlockingStub orgStub;

    @Override
    public ClassInfo getClass(UUID classId) {
        try {
            GetClassResponse response = orgStub
                .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                .getClass(GetClassRequest.newBuilder()
                    .setClassId(classId.toString())
                    .build());
            return ClassInfo.fromProto(response.getClassInfo());
        } catch (StatusRuntimeException ex) {
            throw mapToDomain(ex, classId);
        }
    }

    private RuntimeException mapToDomain(StatusRuntimeException ex, UUID classId) {
        return switch (ex.getStatus().getCode()) {
            case NOT_FOUND -> new ClassNotFoundException(classId);
            case INVALID_ARGUMENT -> new IllegalArgumentException(ex.getStatus().getDescription());
            case PERMISSION_DENIED -> new PermissionDeniedException("Cross-service permission");
            case FAILED_PRECONDITION -> new BusinessRuleViolationException(
                "cross-service-precondition",
                "Precondition Failed",
                ex.getStatus().getDescription()
            );
            case DEADLINE_EXCEEDED, UNAVAILABLE -> new TransientServiceException(
                "organization-service ulaşılamıyor"
            );
            default -> new CrossServiceException("organization-service hatası", ex);
        };
    }
}
```

Bu mapping sayesinde academic-service `ClassNotFoundException` fırlatır, `@ControllerAdvice` bunu 404'e çevirir. Frontend her zaman `type=class-not-found` görür — kaynağı (academic'in kendi DB'si veya organization'dan gelen) önemli değil.

### 4.6. Frontend ProblemDetail TypeScript tipi

```typescript
// shared/types/problem.ts
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  correlationId?: string;
  tenantId?: string;
  timestamp?: string;
  errors?: FieldErrorDetail[];
  [extraProperties: string]: unknown;
}

export interface FieldErrorDetail {
  field: string;
  code: string;
  message: string;
}

export function isProblemDetail(data: unknown): data is ProblemDetail {
  return (
    typeof data === 'object' &&
    data !== null &&
    'type' in data &&
    'title' in data &&
    'status' in data
  );
}
```

### 4.7. Lumix problem code namespace

`https://lumix.io/problems/<code>` URI'ları doc sayfasını gösterir (Docusaurus'ta hata kataloğu).

Örnek kodlar:
- `validation-failed`
- `attendance-revision-window-expired`
- `class-capacity-exceeded`
- `class-not-found`
- `student-not-found`
- `invoice-not-found`
- `payment-already-captured`
- `tenant-mismatch`
- `permission-denied`
- `scope-violation`
- `rate-limit-exceeded`
- `internal-error`
- `service-unavailable`
- `gateway-timeout`

Tek bir kod kataloğu (gelecekte bir Docusaurus sayfası) bütün exception code'ları açıklayacak.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Alternatifler

**Alternatif 1 — Custom hata formatı**
"Lumix kendi hata formatı tasarlasın."

Niye elendi:
- Standart varken yeniden uydurma anti-pattern
- Frontend, mobile, 3rd party entegrasyon RFC 7807 olmasını bekler
- Yeni geliştirici öğrenmek zorunda

**Alternatif 2 — Sadece HTTP status code**
"500, 404, 400 yeter — body yok."

Niye elendi:
- Detay yok, debug zor
- Validation hatasında hangi field belirsiz
- i18n için key yok

**Alternatif 3 — GraphQL-style error array**
"errors[] field'ında object array."

Niye elendi:
- GraphQL spec'i; REST API için uygun değil
- 200 OK + errors yapısı kafa karıştırır

**Alternatif 4 — JSON API spec error**
JSON:API spec'inin error formatı.

Niye elendi:
- JSON:API tüm response yapısını dikte eder — Lumix sadece error standart istedi
- RFC 7807 daha minimal, sadece error için

### 5.2. Kabul edilen trade-off'lar

| Trade-off | Maliyet | Mitigation |
|---|---|---|
| Custom field eklemek non-standard | RFC 7807 sadece `errors[]` standart değil | Extension olarak documented |
| `type` URI bakımı | Doc sayfası güncel kalmalı | Otomatik gen veya manuel doc |
| Exception hierarchy büyük | Her domain için exception class | Pragmatik — gerektikçe ekle |
| Frontend her status code öğrenmeli | 5-6 farklı status | Documented + helper function |

### 5.3. Ne zaman gözden geçirilir?

- Eğer Lumix public API yayınlamayı düşünürse — RFC 7807 tutarlılığı zaten doğru karar
- Eğer GraphQL'e geçilirse (planlanmıyor) — error format değişir

## 6. Pratik örnek

### 6.1. Validation hatası — başlangıç sonuç

**Request:**
```http
POST /api/v1/attendance
Content-Type: application/json

{
  "date": "2026-05-27",
  "marks": []
}
```

**Response:**
```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type": "https://lumix.io/problems/validation-failed",
  "title": "Validation Failed",
  "status": 400,
  "detail": "Bir veya daha fazla field hatalı",
  "instance": "/api/v1/attendance",
  "correlationId": "8c7e2a1f-3d4b-4e5c-9a8f-1b2c3d4e5f6a",
  "tenantId": "01J5MP3K5N1XYZ",
  "timestamp": "2026-05-27T10:30:00Z",
  "errors": [
    { "field": "classId", "code": "NotNull", "message": "class_id zorunlu" },
    { "field": "marks", "code": "NotEmpty", "message": "En az bir öğrenci işaretlenmeli" }
  ]
}
```

### 6.2. İş kuralı ihlali (422)

**Request:**
```http
PUT /api/v1/attendance/01J5MP3K5N1XYZ
Content-Type: application/json

{
  "studentId": "01J5MP3...",
  "presence": "ABSENT"
}
```

**Response:**
```http
HTTP/1.1 422 Unprocessable Entity
Content-Type: application/problem+json

{
  "type": "https://lumix.io/problems/attendance-revision-window-expired",
  "title": "Attendance Revision Window Expired",
  "status": 422,
  "detail": "Yoklama 24 saat içinde revize edilebilir; bu yoklama 48 saat önce alındı.",
  "instance": "/api/v1/attendance/01J5MP3K5N1XYZ",
  "correlationId": "...",
  "tenantId": "...",
  "timestamp": "...",
  "attendanceId": "01J5MP3K5N1XYZ",
  "elapsedHours": 48
}
```

### 6.3. Not Found (404)

```http
GET /api/v1/classes/00000000-0000-0000-0000-000000000000
```

```json
{
  "type": "https://lumix.io/problems/class-not-found",
  "title": "Class Not Found",
  "status": 404,
  "detail": "Class bulunamadı: 00000000-0000-0000-0000-000000000000",
  "instance": "/api/v1/classes/00000000-0000-0000-0000-000000000000",
  "correlationId": "..."
}
```

### 6.4. Permission Denied (403)

```http
DELETE /api/v1/students/01J5MP3...
```

```json
{
  "type": "https://lumix.io/problems/permission-denied",
  "title": "Permission Denied",
  "status": 403,
  "detail": "Bu işlem için 'student:delete' permission'ı gerekli",
  "instance": "/api/v1/students/01J5MP3...",
  "correlationId": "...",
  "requiredPermission": "student:delete"
}
```

### 6.5. Internal Error (500)

```json
{
  "type": "https://lumix.io/problems/internal-error",
  "title": "Internal Server Error",
  "status": 500,
  "detail": "Beklenmeyen bir hata oluştu. correlationId ile destek ekibine başvurabilirsiniz.",
  "instance": "/api/v1/attendance",
  "correlationId": "8c7e2a1f-...",
  "timestamp": "2026-05-27T10:30:00Z"
}
```

Stack trace **asla** response'a girmez. Sadece backend log'da.

### 6.6. Exception unit test

```java
class GlobalExceptionHandlerTest {

    @Test
    void shouldMapNotFoundExceptionTo404() throws Exception {
        // MockMvc setup ...
        mockMvc.perform(get("/api/v1/classes/00000000-0000-0000-0000-000000000000"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value(endsWith("class-not-found")))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.title").value("Class Not Found"));
    }

    @Test
    void shouldMapBusinessRuleViolationTo422() throws Exception {
        when(reviseAttendanceUseCase.execute(any()))
            .thenThrow(new AttendanceRevisionWindowExpiredException(
                AttendanceId.of("test"), Duration.ofHours(48)));

        mockMvc.perform(put("/api/v1/attendance/test").content("..."))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.elapsedHours").value(48));
    }

    @Test
    void shouldHideStackTraceFromResponse() throws Exception {
        when(useCase.execute(any())).thenThrow(new NullPointerException("internal bug"));

        mockMvc.perform(post("/api/v1/attendance").content("..."))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value(not(containsString("NullPointerException"))));
    }
}
```

### 6.7. Frontend kullanım

```typescript
// features/attendance/api.ts
const attendanceApi = createApi({
  // ...
  endpoints: builder => ({
    markAttendance: builder.mutation<AttendanceResponse, MarkAttendanceRequest>({
      query: req => ({ url: '/attendance', method: 'POST', body: req }),
      // RTK Query, baseQuery yakalar
    }),
  }),
});

// Component
const [markAttendance, { error }] = useMarkAttendanceMutation();

useEffect(() => {
  if (error && isProblemDetail(error.data)) {
    const problem = error.data;
    if (problem.type.endsWith('validation-failed')) {
      problem.errors?.forEach(e => {
        form.setError(e.field, { message: e.message });
      });
    } else if (problem.type.endsWith('attendance-revision-window-expired')) {
      toast.error(`Revize süresi doldu (${problem.elapsedHours} saat geçmiş)`);
    } else {
      toast.error(problem.title);
    }
  }
}, [error]);
```

## 7. Dikkat edilecek tuzaklar

**Tuzak 1 — Stack trace'i response'a koymak.**
Debug için exception detail'i client'a gönderilir → security risk.
**Önleme:** Stack trace asla response'a. Log'a + correlation-id ile.

**Tuzak 2 — i18n karıştırması.**
Backend Türkçe message dönüyor, mobile İngilizce kullanıcı görüyor.
**Önleme:** `type` URI sabit (i18n key gibi). `detail` opsiyonel i18n. Frontend `type`'a göre kendi mesajını gösterir.

**Tuzak 3 — Hata mesajında sensitive info.**
"Email 'a@b.com' kayıtlı değil" — enumeration attack. "Password yanlış: bekleniyor 'X'..."
**Önleme:** Hata mesajları generic. Auth fail için her zaman "Email veya password yanlış".

**Tuzak 4 — Status code yanlış.**
"Class bulunamadı" 500 dönüyor. Veya validation hatası 422 dönüyor (400 olmalı).
**Önleme:** Standart eşleme tablosu. Code review.

**Tuzak 5 — Content type yanlış.**
`application/json` dönmek RFC 7807 spec'e aykırı. `application/problem+json` olmalı.
**Önleme:** `MediaType.APPLICATION_PROBLEM_JSON` her hata cevabında.

**Tuzak 6 — Type URI hiç dokümante edilmemiş.**
`https://lumix.io/problems/foo` — bu URL açıldığında 404. Frontend developer "bu nedir" diyor.
**Önleme:** Docusaurus'ta hata kataloğu sayfası, her code dokümante.

**Tuzak 7 — Inconsistent error format.**
Bir servis RFC 7807, başka servis custom format. Frontend her servis için ayrı parser.
**Önleme:** Tüm servislerde shared exception handler veya autoconfigure pattern.

**Tuzak 8 — Correlation id eksik.**
Hata oldu, log'a düştü, ama client'taki hata mesajıyla bağlanamıyor.
**Önleme:** correlation-id her response'da. Frontend gösterir, kullanıcı destek ekibine söyler.

**Tuzak 9 — Validation hatasında errors[] yok.**
"Bad request" diyor, hangi field belli değil.
**Önleme:** `errors[]` her validation 400'ünde zorunlu.

**Tuzak 10 — 500'leri sessizce yutmak.**
Generic "internal error" döner ama log'a düşmez. Hiç fark etmezsin.
**Önleme:** Unhandled exception handler her zaman log.error + alert.

**Tuzak 11 — Cross-service exception sızıntısı.**
gRPC `Status.NOT_FOUND` direkt 500 olarak frontend'e iletilir.
**Önleme:** Client tarafında gRPC status → domain exception mapping.

**Tuzak 12 — `@ExceptionHandler` sırası yanlış.**
`Exception.class` handler'ı en üstte → spesifik handler'lar hiç tetiklenmiyor.
**Önleme:** En spesifik exception en üstte; `Exception.class` en sonda (Spring zaten priority handle eder ama doğru sıra okunabilirlik).

## 8. Diğer konularla ilişkisi

- [Validation Strategy](./04-validation-strategy.md) — validation hatalarının exception'a dönüşümü
- [gRPC Service Communication](./03-grpc-service-communication.md) — gRPC status → exception mapping
- [Spring Boot Foundation](./01-spring-boot-foundation.md) — `@ControllerAdvice` Spring entegrasyonu
- [Microservices Architecture](../02-architecture-patterns/01-microservices-architecture.md) — cross-service error format tutarlılığı
- [Hexagonal Architecture](../02-architecture-patterns/03-hexagonal-architecture.md) — exception domain'de, mapping adapter'da

## 9. Daha derine inmek için

**Resmi:**
- RFC 7807: tools.ietf.org/html/rfc7807
- RFC 9457 (güncelleme): datatracker.ietf.org/doc/html/rfc9457
- Spring Framework Reference — Web > Error Handling

**Blog / makaleler:**
- Baeldung — "Spring 6: ProblemDetail"
- Spring.io blog — error handling articles

**Search keywords:**
- "rfc 7807 problem details spring boot 3"
- "spring controlleradvice exception handler"
- "rest api error handling best practices"
- "problem+json content type"
- "grpc rest error mapping"
- "spring validator exception 400"

## 10. Sözlük

- **`@ControllerAdvice`** — Spring'in global exception handler annotation'ı.
- **`@ExceptionHandler`** — Belirli exception'ı yakalayan method annotation'ı.
- **`application/problem+json`** — RFC 7807 content type.
- **Correlation ID** — Tek bir request/akışın tüm sinyallerine bağlanan stabil tanımlayıcı.
- **Domain Exception** — İş kuralı veya domain hatası için fırlatılan exception.
- **Exception Hierarchy** — Exception sınıflarının inheritance ağacı.
- **HTTP Status Code** — Response'un kategorisini gösteren integer (200, 400, 500 vs).
- **Problem Code** — Lumix'te exception'ın benzersiz isim (URI path'inde).
- **`ProblemDetail`** — Spring 6'da RFC 7807 problem details için sınıf.
- **RFC 7807** — Problem Details for HTTP APIs standardı.
- **RFC 9457** — RFC 7807'nin güncellenmiş hali (2023).
- **Stack Trace** — Exception'ın oluştuğu çağrı zinciri. **Asla** response'a koymayın.
- **Title** — Hatanın human-readable kısa özeti (problem type başına sabit).
