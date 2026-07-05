---
title: gRPC Service Communication
description: gRPC nedir, Protobuf ile çalışma, .proto tanımı, stub generation, Spring Boot gRPC server/client, interceptor, deadline, retry ve Lumix kullanımı.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Bu sayfa **gRPC**'yi sıfırdan anlatıyor: HTTP/2 + Protobuf üstüne kurulu bu RPC framework'ünün ne yaptığını, **Protobuf** ile birlikte schema tanımının nasıl yapıldığını, **Gradle plugin** ile **stub generation**'ı, **Spring Boot'ta gRPC server ve client** kurulumunu, **interceptor**, **deadline**, **retry** mekanizmalarını ve Lumix'te servisler arası sync iletişim için neden seçildiğini gösteriyor. Sonunda okuyan biri yeni bir gRPC API tanımlayıp implement edebilecek, başka servisten çağırabilecek seviyede olmalı.

## 1. Bu nedir? (Sıfırdan)

**gRPC** = Google Remote Procedure Call. Google'ın açık kaynak (2015), HTTP/2 + Protobuf üstüne kurulu yüksek performanslı RPC framework'üdür. Adındaki "g" Google'dan gelir ama her sürümde anlamı değişiyor ("gentle", "good", "green"... şaka).

**RPC nedir?**
"Remote Procedure Call" = uzaktaki bir fonksiyonu sanki lokal fonksiyon gibi çağırma. JavaScript dünyasındaki AJAX call'ları, REST API çağrıları da bir tür RPC. Ama klasik RPC framework'leri (SOAP, XML-RPC, JSON-RPC) bugün eski. gRPC yeni nesil.

**gRPC'nin temel özellikleri:**

1. **HTTP/2 transport** — multiplexing, header compression, binary framing
2. **Protobuf serialization** — binary, schema-first, code-gen
3. **Code generation** — `.proto` dosyasından Java/Go/Python/JS stub üretimi
4. **Type safety** — derleyici zamanı kontrol
5. **Bi-directional streaming** — client/server stream messages
6. **Built-in deadline/timeout** — context-based
7. **Pluggable auth, interceptor, load balancing**

**Günlük hayattan analoji:**
REST = mektuplaşmak. Her mektup ayrı zarf (HTTP header), uzun adres yazımı, içinde HTML/JSON metni. Hızlı değil ama herkes anlar.

gRPC = telsiz konuşma. Aynı frekansta, kısa kodlanmış mesajlar (Protobuf binary), iki taraf da kod kitabını biliyor, hızlı ve net.

### Protobuf nedir?

**Protocol Buffers (Protobuf)** = Google'ın açık kaynak binary serialization formatı. Schema (`.proto` dosyası) bir kez yazılır, her dile code-gen ile çevrilir. JSON'a göre **3-10x daha küçük**, **3-5x daha hızlı parse**.

```proto
syntax = "proto3";

message Student {
  string id = 1;
  string name = 2;
  int32 age = 3;
  repeated string class_ids = 4;
}
```

Bunu `protoc` derler → Java/Go/Python/JS class.

### gRPC çağrı tipleri

1. **Unary RPC:** Tek request, tek response (REST'e en yakın).
2. **Server streaming:** Tek request, çok response. Server peyderpey gönderir.
3. **Client streaming:** Çok request, tek response. Client peyderpey gönderir.
4. **Bidirectional streaming:** İki taraf da peyderpey gönderir.

Lumix'te çoğu kullanım: **Unary**. Streaming ileride büyük dosya transfer veya real-time log için kullanılabilir.

## 2. Hangi problemi çözüyor?

REST + JSON ile microservice iletişiminde tipik acılar:

**Acı 1 — Sözleşme yok, sadece dokümantasyon.**
"Bu endpoint'in body'sinde `studentId` field'ı kebab-case mi camelCase mi?" Postman koleksiyonu, OpenAPI doc, Wiki page — hepsi bir noktada güncel olmaktan çıkar.

**Acı 2 — Type safety yok.**
Client tarafı `student.name` string sandı, server `name` field'ını object yaptı. Runtime'da fail. Test'le yakaladıysan iyi, yoksa production'da.

**Acı 3 — Performans.**
JSON parse + serialize her request için CPU yer. Büyük payload'larda fark belirgin. 10ms vs 2ms.

**Acı 4 — Schema evolution kaosu.**
Server `birthDate` field'ı ekledi, eski client kırıldı çünkü "additionalProperties: false" katı validation.

**Acı 5 — Streaming yok.**
REST tipik olarak request-response. Server-Sent Events veya WebSocket gerekirse ayrı bir kurulum.

**Acı 6 — Multiple language code-gen yok.**
Her dilde DTO sınıflarını elle yazmak. Java client, Python client, JS frontend — üç kez aynı şema.

**Acı 7 — Header overhead.**
HTTP/1.1 her request için header tekrar gönderir. 200 byte body için 1000 byte header.

gRPC bu acıları şöyle çözer:

| Acı | gRPC çözümü |
|---|---|
| Sözleşme yok | `.proto` dosyası source of truth |
| Type safety yok | Code-gen ile derleyici kontrolü |
| Performans | Binary Protobuf + HTTP/2 |
| Schema evolution | Protobuf'ın forward/backward compatibility kuralları |
| Streaming yok | Native server/client/bidi streaming |
| Multi-language gen yok | protoc her dile gen yapar |
| Header overhead | HTTP/2 HPACK compression |

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. `.proto` dosyası — sözleşme

```proto
// proto/identity/v1/identity_service.proto
syntax = "proto3";

package com.lumix.identity.v1;

option java_package = "com.lumix.proto.identity.v1";
option java_multiple_files = true;
option java_outer_classname = "IdentityProto";

import "google/protobuf/timestamp.proto";
import "google/protobuf/empty.proto";

service IdentityService {
  rpc ValidateToken(ValidateTokenRequest) returns (ValidateTokenResponse);
  rpc GetUserPermissions(GetUserPermissionsRequest) returns (GetUserPermissionsResponse);
  rpc RevokeTokensForUser(RevokeTokensForUserRequest) returns (google.protobuf.Empty);
}

message ValidateTokenRequest {
  string token = 1;
}

message ValidateTokenResponse {
  bool valid = 1;
  string user_id = 2;
  string tenant_id = 3;
  repeated string permissions = 4;
  google.protobuf.Timestamp expires_at = 5;
}

message GetUserPermissionsRequest {
  string user_id = 1;
  string tenant_id = 2;
}

message GetUserPermissionsResponse {
  repeated string permissions = 1;
}

message RevokeTokensForUserRequest {
  string user_id = 1;
  string reason = 2;
}
```

### 3.2. Code-gen — `protoc` ile

`com.google.protobuf` **protobuf-gradle-plugin** ile Gradle build time'da `.proto` → Java sınıfları.

Generated kodlar (sample):
- `IdentityServiceGrpc.IdentityServiceBlockingStub` — synchronous client
- `IdentityServiceGrpc.IdentityServiceFutureStub` — async client
- `IdentityServiceGrpc.IdentityServiceStub` — streaming-ready client
- `IdentityServiceGrpc.IdentityServiceImplBase` — server base class
- `ValidateTokenRequest`, `ValidateTokenResponse` — message class'lar (`.toBuilder()`, `Builder` pattern)

### 3.3. HTTP/2 transport

REST = HTTP/1.1 (genelde). Her request ayrı TCP connection veya keep-alive üzeri sıralı.
gRPC = HTTP/2:
- **Multiplexing:** tek TCP connection üstünde paralel stream
- **HPACK header compression:** tekrar eden header'lar sıkıştırılır
- **Binary framing:** parsing daha hızlı
- **Server push:** (gRPC kullanmıyor ama HTTP/2 destekli)

### 3.4. Schema evolution kuralları

Protobuf field number bazlı evolution destekler:

```proto
message Student {
  string id = 1;          // mevcut
  string name = 2;        // mevcut
  // int32 age = 3;       // kaldırıldı (reserved)
  reserved 3;             // tekrar kullanılmasın
  string email = 4;       // yeni field
  optional string phone = 5;  // yeni optional
}
```

Kurallar:
- Field number **değişmez** (mevcut field'ın numarası asla kaldırılmaz, reserve edilir)
- Yeni field ekle = backward compatible (eski client ignore eder)
- Field sil = reserved işaretle (numara tekrar kullanılamaz)
- Type değiştirme = breaking

Apicurio Schema Registry **BACKWARD compatibility** enforce eder.

### 3.5. Deadline / Timeout

REST'te genelde "30 saniye timeout" client tarafında ayarlanır. gRPC'de **deadline** her çağrıda set edilir ve **server tarafına propagate olur**:

```java
identityStub
    .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
    .validateToken(request);
```

Server-side: 500ms sonra deadline doldu → server iş yapmaktan vazgeçer (context cancelled), client `DEADLINE_EXCEEDED` alır.

### 3.6. Status codes

REST'in HTTP status code'larına benzer ama gRPC kendi standardı:

| Code | Anlam | Kullanım |
|---|---|---|
| `OK` | Başarılı | Default |
| `INVALID_ARGUMENT` | Geçersiz input | Validation hatası |
| `NOT_FOUND` | Bulunamadı | Resource yok |
| `ALREADY_EXISTS` | Zaten var | Duplicate create |
| `PERMISSION_DENIED` | Yetki yok | Authz fail |
| `UNAUTHENTICATED` | Auth yok | Token yok/geçersiz |
| `FAILED_PRECONDITION` | İş kuralı ihlali | Business rule fail |
| `RESOURCE_EXHAUSTED` | Quota aşıldı | Rate limit |
| `INTERNAL` | Sunucu hatası | Bug, exception |
| `UNAVAILABLE` | Servis ulaşılmaz | Down, retry-safe |
| `DEADLINE_EXCEEDED` | Timeout | Yavaş yanıt |

### 3.7. Interceptor

gRPC'nin "middleware"i. Hem client hem server'da çalışır.

Client interceptor — auth header ekleme:
```java
public class JwtClientInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next
    ) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<RespT> listener, Metadata headers) {
                headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                            "Bearer " + currentJwt());
                super.start(listener, headers);
            }
        };
    }
}
```

Server interceptor — context kurma, log, tracing.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. gRPC nerede kullanılıyor?

Lumix'te **sync inter-service iletişim** için gRPC. Async = Kafka.

**Tipik kullanım:**
- `academic-service` → `organization-service`: "Bu class hangi tenant'a ait?"
- Tüm servisler → `identity-service`: "Bu token valid mi, hangi permission'lar var?"
- `communication-service` → `file-service`: "Bu file scan edildi mi?"
- `finance-service` → `organization-service`: "Bu student'ın velisi kim?"

**Kullanmadığımız yerler:**
- Frontend ↔ Backend (REST tutuyoruz — browser/mobile gRPC desteği zayıf, gRPC-Web extra layer)
- Service ↔ Service async (Kafka)
- Service ↔ Workflow (Temporal kendi RPC'sini kullanır)

### 4.2. Proto dosya organizasyonu

Lumix'te **shared proto repository** yerine her servis kendi `.proto` dosyalarını **kendi repo'sunda** tutar.

```
academic-service/
├── src/main/proto/
│   └── com/lumix/academic/v1/
│       └── attendance_service.proto

identity-service/
├── src/main/proto/
│   └── com/lumix/identity/v1/
│       └── identity_service.proto
```

Diğer servisler bu proto'yu kendi build sürecinde **kopyalar** veya **Gradle module/artifact dependency** olarak çeker.

> **Trade-off:** Shared proto repo daha basit görünür ama **microservice bağımsızlığı** ile çelişir. Lumix kararı: shared library yok prensibi proto için de geçerli. Duplicate proto kabul edildi.

Daha sonra: `buf` (Buf Schema Registry) gibi araçlarla schema artifact dağıtılabilir.

### 4.3. Gradle plugin konfigürasyonu

Codegen, projenin Gradle (Kotlin DSL) build'inde `com.google.protobuf` **protobuf-gradle-plugin** ile yapılır — `protoc` sistemden değil, **artifact olarak** çözülür (reproducible, cross-platform). Sürümler version catalog'dan sürülür ki codegen ve runtime kütüphaneleri birlikte güncellensin.

`gradle/libs.versions.toml`:
```toml
[versions]
grpc = "1.66.0"
protobuf = "3.25.5"
protobufPlugin = "0.9.5"

[plugins]
protobuf = { id = "com.google.protobuf", version.ref = "protobufPlugin" }
```

`build.gradle.kts`:
```kotlin
plugins {
    java
    alias(libs.plugins.protobuf)
}

dependencies {
    implementation(platform("io.grpc:grpc-bom:${libs.versions.grpc.get()}"))
    implementation("io.grpc:grpc-netty-shaded")
    implementation("io.grpc:grpc-protobuf")
    implementation("io.grpc:grpc-stub")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53") // generated kod anotasyonları

    // Spring Boot gRPC starter (net.devh — bkz. 4.8: Spring gRPC geçiş notu)
    implementation("net.devh:grpc-server-spring-boot-starter:3.1.0")
    implementation("net.devh:grpc-client-spring-boot-starter:3.1.0")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}" }
    }
    generateProtoTasks {
        all().forEach { it.plugins { id("grpc") } }
    }
}
```

`.proto` dosyaları `src/main/proto/` altında; üretilen kod `build/generated/sources/proto/`'ya gelir ve source set'e otomatik eklenir. `./gradlew build` codegen'i tetikler.

### 4.4. Server tarafı — Spring Boot

```yaml
# application.yml
grpc:
  server:
    port: 9090
    security:
      enabled: false  # internal cluster trafiği için, mTLS ileride
```

```java
// adapter/in/grpc/IdentityGrpcService.java
@GrpcService
@RequiredArgsConstructor
public class IdentityGrpcService extends IdentityServiceGrpc.IdentityServiceImplBase {

    private final ValidateTokenUseCase validateTokenUseCase;
    private final GetUserPermissionsUseCase getUserPermissionsUseCase;

    @Override
    public void validateToken(
        ValidateTokenRequest request,
        StreamObserver<ValidateTokenResponse> responseObserver
    ) {
        try {
            TokenValidationResult result = validateTokenUseCase.execute(
                new ValidateTokenCommand(request.getToken())
            );

            ValidateTokenResponse response = ValidateTokenResponse.newBuilder()
                .setValid(result.valid())
                .setUserId(result.userId().toString())
                .setTenantId(result.tenantId().toString())
                .addAllPermissions(result.permissions())
                .setExpiresAt(toTimestamp(result.expiresAt()))
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (TokenNotFoundException ex) {
            responseObserver.onError(Status.NOT_FOUND
                .withDescription("Token not found")
                .asRuntimeException());
        } catch (Exception ex) {
            log.error("validateToken failed", ex);
            responseObserver.onError(Status.INTERNAL
                .withDescription("Internal error")
                .asRuntimeException());
        }
    }
}
```

### 4.5. Client tarafı — Spring Boot

```yaml
# application.yml (calling service)
grpc:
  client:
    identity-service:
      address: dns:///identity-service.default.svc.cluster.local:9090
      negotiationType: plaintext  # internal, mTLS ileride
      enableKeepAlive: true
      keepAliveTime: 30
      keepAliveTimeout: 5
```

```java
// adapter/out/grpc/GrpcIdentityClient.java
@Component
@RequiredArgsConstructor
@Slf4j
public class GrpcIdentityClient implements IdentityClient {

    @GrpcClient("identity-service")
    private IdentityServiceGrpc.IdentityServiceBlockingStub identityStub;

    @Override
    public TokenValidationResult validateToken(String token) {
        try {
            ValidateTokenResponse response = identityStub
                .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                .validateToken(ValidateTokenRequest.newBuilder()
                    .setToken(token)
                    .build());

            return TokenValidationResult.builder()
                .valid(response.getValid())
                .userId(UUID.fromString(response.getUserId()))
                .tenantId(UUID.fromString(response.getTenantId()))
                .permissions(response.getPermissionsList())
                .expiresAt(toInstant(response.getExpiresAt()))
                .build();
        } catch (StatusRuntimeException ex) {
            Status.Code code = ex.getStatus().getCode();
            if (code == Status.Code.NOT_FOUND) {
                throw new TokenNotFoundException();
            }
            if (code == Status.Code.DEADLINE_EXCEEDED) {
                throw new ServiceUnavailableException("identity-service yavaş yanıt verdi");
            }
            throw new IdentityServiceException("validateToken failed", ex);
        }
    }
}
```

### 4.6. Interceptor — auth + tracing

```java
@Component
public class CorrelationIdServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> CORRELATION_ID_KEY =
        Metadata.Key.of("correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call,
        Metadata headers,
        ServerCallHandler<ReqT, RespT> next
    ) {
        String correlationId = Optional.ofNullable(headers.get(CORRELATION_ID_KEY))
            .orElse(UUID.randomUUID().toString());
        MDC.put("correlation-id", correlationId);

        Context context = Context.current().withValue(
            Constants.CORRELATION_ID, correlationId
        );
        return Contexts.interceptCall(context, call, headers, next);
    }
}

@Configuration
public class GrpcConfig {
    @GrpcGlobalServerInterceptor
    public ServerInterceptor correlationIdInterceptor() {
        return new CorrelationIdServerInterceptor();
    }
}
```

### 4.7. Retry — client tarafında

```yaml
grpc:
  client:
    identity-service:
      address: dns:///identity-service.default.svc.cluster.local:9090
      enableKeepAlive: true
```

Service config (`grpc-service-config.json`):
```json
{
  "methodConfig": [
    {
      "name": [
        { "service": "com.lumix.identity.v1.IdentityService" }
      ],
      "retryPolicy": {
        "maxAttempts": 3,
        "initialBackoff": "0.1s",
        "maxBackoff": "1s",
        "backoffMultiplier": 2,
        "retryableStatusCodes": ["UNAVAILABLE", "DEADLINE_EXCEEDED"]
      }
    }
  ]
}
```

> Retry sadece **idempotent** RPC'ler için. Read RPC'leri retry-safe; write için idempotency key gerekir.

### 4.8. Lumix gRPC kararları

| Konu | Karar |
|---|---|
| Library | `grpc-java` (resmi) + `net.devh` starter (superseded → yeni projede **Spring gRPC** `org.springframework.grpc`; Boot 3.x'te 0.x hattı, Boot 4.x'te 1.0 GA) |
| Code-gen | `protobuf-gradle-plugin` (`com.google.protobuf`); build-time `.proto`→Java/stub, sürümler version catalog + `grpc-bom`'dan |
| Transport | HTTP/2 cleartext (h2c) cluster içi, mTLS gelecekte |
| Schema dağıtım | Her servis kendi proto'su (shared lib yok) |
| Versioning | Proto package'da `.v1`, `.v2` |
| Deadline | Default 500ms, business'a göre override |
| Retry | Idempotent read RPC'leri için, write için manuel |
| Compression | gzip header destekli |
| Health check | gRPC health check protocol |
| Reflection | Dev/staging açık, prod kapalı (security) |

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Alternatifler

**Alternatif 1 — REST + JSON**
Klasik, browser native.

Niye elendi (inter-service için):
- Type safety yok
- Schema sözleşmesi zayıf
- Performance JSON parse overhead
- Streaming yok

REST sadece **frontend ↔ backend** için kullanılır (browser uyumu).

**Alternatif 2 — GraphQL**
Single endpoint, esnek query.

Niye elendi:
- Inter-service iletişim için over-engineered
- N+1 query riski
- Caching karmaşık
- Frontend için bile Lumix'in REST'i yetiyor

**Alternatif 3 — Thrift (Apache)**
gRPC'ye benzer ama daha eski.

Niye elendi:
- Topluluk ve tooling gRPC'den küçük
- HTTP/2 native değil

**Alternatif 4 — Avro RPC**
Avro schema ile RPC.

Niye elendi:
- gRPC kadar olgun değil
- Lumix zaten Protobuf seçti (Kafka için)

### 5.2. Kabul edilen trade-off'lar

| Trade-off | Maliyet | Mitigation |
|---|---|---|
| Browser-friendly değil | Direkt browser'dan çağrılmaz | Frontend ↔ Backend REST |
| Debug zorluğu | Binary, curl'la test edilemez | grpcurl, BloomRPC, gRPC UI |
| Code-gen build adımı | Her change'te regenerate | Gradle plugin otomatik |
| Stub size | Generated kod büyük | Trade-off kabul |
| Steep learning curve | Ekip Protobuf öğrenmeli | İlk hafta öğrenilir |
| HTTP/2 cluster içi | Eski LB'lerde sorun | K8s native HTTP/2 destekli |

### 5.3. Ne zaman REST tercih edilir?

- Frontend ↔ Backend (browser uyumu)
- 3rd party webhook entegrasyonu (CSP, payment provider callback)
- Public API (gRPC public API daha az yaygın)
- Çok basit endpoint (overhead yarar değil)

Lumix'te frontend backend REST, internal gRPC ikilisi standart.

## 6. Pratik örnek

### 6.1. Tam akış — academic-service → organization-service

**organization-service'in proto'su:**

```proto
// organization-service/src/main/proto/com/lumix/organization/v1/organization_service.proto
syntax = "proto3";

package com.lumix.organization.v1;

option java_package = "com.lumix.proto.organization.v1";
option java_multiple_files = true;

service OrganizationService {
  rpc GetClass(GetClassRequest) returns (GetClassResponse);
  rpc GetClassesForTeacher(GetClassesForTeacherRequest) returns (GetClassesForTeacherResponse);
}

message GetClassRequest {
  string class_id = 1;
}

message GetClassResponse {
  ClassInfo class_info = 1;
}

message ClassInfo {
  string class_id = 1;
  string tenant_id = 2;
  string name = 3;
  string school_id = 4;
  int32 grade_level = 5;
  int32 capacity = 6;
}

message GetClassesForTeacherRequest {
  string teacher_id = 1;
  string tenant_id = 2;
}

message GetClassesForTeacherResponse {
  repeated ClassInfo classes = 1;
}
```

**organization-service server tarafı:**

```java
@GrpcService
@RequiredArgsConstructor
@Slf4j
public class OrganizationGrpcService extends OrganizationServiceGrpc.OrganizationServiceImplBase {

    private final GetClassUseCase getClassUseCase;

    @Override
    public void getClass(GetClassRequest request, StreamObserver<GetClassResponse> obs) {
        try {
            UUID classId = UUID.fromString(request.getClassId());
            ClassInfo info = getClassUseCase.execute(new GetClassQuery(classId));

            GetClassResponse response = GetClassResponse.newBuilder()
                .setClassInfo(toProto(info))
                .build();

            obs.onNext(response);
            obs.onCompleted();
        } catch (IllegalArgumentException ex) {
            obs.onError(Status.INVALID_ARGUMENT
                .withDescription("Geçersiz class_id formatı")
                .asRuntimeException());
        } catch (ClassNotFoundException ex) {
            obs.onError(Status.NOT_FOUND
                .withDescription("Class bulunamadı")
                .asRuntimeException());
        } catch (Exception ex) {
            log.error("getClass failed", ex);
            obs.onError(Status.INTERNAL.asRuntimeException());
        }
    }

    private com.lumix.proto.organization.v1.ClassInfo toProto(ClassInfo info) {
        return com.lumix.proto.organization.v1.ClassInfo.newBuilder()
            .setClassId(info.id().toString())
            .setTenantId(info.tenantId().toString())
            .setName(info.name())
            .setSchoolId(info.schoolId().toString())
            .setGradeLevel(info.gradeLevel())
            .setCapacity(info.capacity())
            .build();
    }
}
```

**academic-service client tarafı:**

```java
// application/port/out/OrganizationClient.java
public interface OrganizationClient {
    ClassInfo getClass(UUID classId);
}

// adapter/out/grpc/GrpcOrganizationClient.java
@Component
@RequiredArgsConstructor
@Slf4j
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
            return handleGrpcError(ex, classId);
        }
    }

    private ClassInfo handleGrpcError(StatusRuntimeException ex, UUID classId) {
        Status.Code code = ex.getStatus().getCode();
        switch (code) {
            case NOT_FOUND -> throw new ClassNotFoundException(classId);
            case INVALID_ARGUMENT -> throw new IllegalArgumentException("Invalid class_id");
            case DEADLINE_EXCEEDED, UNAVAILABLE ->
                throw new TransientServiceException("organization-service yavaş");
            default -> {
                log.error("organization-service gRPC error: {}", code, ex);
                throw new CrossServiceException("organization-service çağrısı başarısız", ex);
            }
        }
    }
}
```

### 6.2. Server-streaming örneği

```proto
service AuditService {
  rpc StreamAuditLogs(StreamAuditLogsRequest) returns (stream AuditLogEntry);
}
```

```java
@Override
public void streamAuditLogs(StreamAuditLogsRequest req, StreamObserver<AuditLogEntry> obs) {
    auditLogRepo.streamForTenant(UUID.fromString(req.getTenantId()))
        .forEach(entry -> {
            try {
                obs.onNext(AuditLogEntry.newBuilder()
                    .setId(entry.id().toString())
                    .setEventType(entry.eventType())
                    .build());
            } catch (Exception ex) {
                obs.onError(ex);
                return;
            }
        });
    obs.onCompleted();
}
```

### 6.3. Test — gRPC server unit test

```java
@SpringBootTest
@DirtiesContext
class IdentityGrpcServiceTest {

    @Autowired ApplicationContext context;
    @Autowired ValidateTokenUseCase validateTokenUseCase;
    @MockBean RealValidateTokenUseCase realUseCase;

    @Test
    void shouldReturnValidResponse() throws Exception {
        // grpc-java in-process channel — gerçek network'siz test
        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(context.getBean(IdentityGrpcService.class))
            .build()
            .start();

        try {
            ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
            IdentityServiceGrpc.IdentityServiceBlockingStub stub =
                IdentityServiceGrpc.newBlockingStub(channel);

            when(realUseCase.execute(any())).thenReturn(TokenValidationResult.valid(...));

            ValidateTokenResponse resp = stub.validateToken(
                ValidateTokenRequest.newBuilder().setToken("test-token").build()
            );

            assertThat(resp.getValid()).isTrue();
            channel.shutdown();
        } finally {
            server.shutdown();
        }
    }
}
```

### 6.4. grpcurl ile debug

```bash
# Reflection açıksa servis listele
grpcurl -plaintext localhost:9090 list

# Method listele
grpcurl -plaintext localhost:9090 list com.lumix.identity.v1.IdentityService

# Çağrı yap
grpcurl -plaintext \
  -d '{"token":"abc.def.ghi"}' \
  localhost:9090 \
  com.lumix.identity.v1.IdentityService/ValidateToken
```

### 6.5. Health check

```java
@Component
public class GrpcHealthService extends HealthGrpc.HealthImplBase {

    @Override
    public void check(HealthCheckRequest request, StreamObserver<HealthCheckResponse> obs) {
        // Kontrol DB, Kafka, dependencies
        HealthCheckResponse.ServingStatus status = (depsHealthy())
            ? HealthCheckResponse.ServingStatus.SERVING
            : HealthCheckResponse.ServingStatus.NOT_SERVING;
        obs.onNext(HealthCheckResponse.newBuilder().setStatus(status).build());
        obs.onCompleted();
    }
}
```

K8s readiness probe `grpc_health_probe` ile bunu çağırır:
```yaml
readinessProbe:
  exec:
    command: ["grpc_health_probe", "-addr=:9090"]
```

## 7. Dikkat edilecek tuzaklar

**Tuzak 1 — Deadline koymamak.**
Client çağrı yapar, server takılır, client sonsuza kadar bekler.
**Önleme:** Her çağrıda `withDeadlineAfter(...)`. Default 500ms.

**Tuzak 2 — Status code'larını yanlış kullanmak.**
Her hata `INTERNAL` döndürülür — client retry edip retry edemeyeceğini bilemez.
**Önleme:** Anlamlı status code: `NOT_FOUND`, `INVALID_ARGUMENT`, `FAILED_PRECONDITION`, `UNAVAILABLE` doğru kullan.

**Tuzak 3 — Field number'ı değiştirmek.**
`student_id = 1` field'ını `id = 1` yaparsın → eski client kırılır.
**Önleme:** Field number değişmez. İsim değişebilir (binary serialization number-based), ama dikkat.

**Tuzak 4 — Field silinen number'ı tekrar kullanmak.**
`age = 3` silindi, 6 ay sonra `phone = 3` eklendi. Eski client'ın age field'ı `phone` ile çatışır.
**Önleme:** `reserved 3;` ile lock'la.

**Tuzak 5 — Default değer karışıklığı.**
Proto3'te tüm field'lar default değerli (`""`, `0`, `false`). "Bu field set edildi mi yoksa default mı?" ayırt edilemez.
**Önleme:** Optional field'lar için `optional` keyword (Proto3 üzerinde son güncellemeyle).

**Tuzak 6 — Streaming'i hatalı kapatmak.**
`onCompleted()` çağrılmadan stream sonsuz açık kalır. Memory leak.
**Önleme:** Her başarılı stream'i `onCompleted()`. Her hatayı `onError()`.

**Tuzak 7 — Büyük message göndermek.**
Default 4MB limit. Üzerine çıkınca `RESOURCE_EXHAUSTED` döner.
**Önleme:** Limit ayarla (`maxMessageSize`) veya streaming kullan. Genelde büyük data Kafka veya RustFS.

**Tuzak 8 — Sync sync sync zinciri.**
A → B → C → D, hepsi sync gRPC. Tek bir servis yavaşlarsa zincir çöker.
**Önleme:** Sync 2 hop'u geçmesin. Event-driven flow tercih.

**Tuzak 9 — Reflection prod'da açık.**
gRPC reflection servisi production'da açık → API yapısı sızdırılır.
**Önleme:** Prod'da kapat. Dev/staging'de açık.

**Tuzak 10 — Connection per call.**
Her çağrı için yeni gRPC channel açmak — overhead çok.
**Önleme:** Channel singleton (Spring `@GrpcClient` zaten yapıyor). Long-lived.

**Tuzak 11 — Error detail'i Status'a sığdırmak.**
"Tek bir error message string yeter" — structured detail kayıp.
**Önleme:** `com.google.rpc.Status` ile rich error details (RFC 7807 benzeri).

**Tuzak 12 — Protobuf field'ında PII direkt.**
Logging interceptor'ı request'i otomatik log'lar → email, phone leak.
**Önleme:** PII field'ları log redact, audit log ayrı.

## 8. Diğer konularla ilişkisi

- [Microservices Architecture](../02-architecture-patterns/01-microservices-architecture) — gRPC sync iletişim kanalı
- [Event-Driven Architecture](../02-architecture-patterns/04-event-driven-architecture) — Kafka async kanal (gRPC tamamlayıcısı)
- [Hexagonal Architecture](../02-architecture-patterns/03-hexagonal-architecture) — gRPC adapter (in ve out)
- [Spring Boot Foundation](./01-spring-boot-foundation) — gRPC Boot starter
- [Error Handling RFC 7807](./05-error-handling-rfc7807) — gRPC status code mapping
- [Validation Strategy](./04-validation-strategy) — gRPC input validation

## 9. Daha derine inmek için

**Resmi:**
- grpc.io/docs/languages/java
- protobuf.dev/programming-guides/proto3
- developers.google.com/protocol-buffers

**Spring entegrasyonu:**
- github.com/grpc-ecosystem/grpc-spring (LogNet)
- yidongnan/grpc-spring-boot-starter

**Kitaplar:**
- "gRPC: Up and Running" — Kasun Indrasiri, Danesh Kuruppu
- "Practical gRPC" — Joshua Humphries vd.

**Search keywords:**
- "grpc spring boot tutorial"
- "protobuf schema evolution"
- "grpc deadline retry java"
- "grpc interceptor authentication"
- "grpc vs rest performance"
- "grpc health check kubernetes"

## 10. Sözlük

- **Channel** — gRPC client'ın server ile TCP connection'ı (long-lived).
- **Code-gen** — `.proto` dosyasından dil-spesifik class üretimi.
- **Deadline** — RPC'nin tamamlanması gereken son zaman; client tarafı set eder.
- **gRPC** — Google Remote Procedure Call framework.
- **HPACK** — HTTP/2 header compression algoritması.
- **Interceptor** — gRPC'nin middleware'i, request/response zincirinde işlem yapar.
- **Multiplexing** — HTTP/2'nin tek connection üstünde paralel stream desteği.
- **Protobuf** — Google'ın binary serialization formatı + schema dili.
- **protoc** — Protobuf compiler.
- **RPC** — Remote Procedure Call.
- **Status Code** — gRPC'nin kendi response code seti (OK, NOT_FOUND, UNAVAILABLE vs).
- **Streaming** — Server/client/bidi olarak peyderpey mesajlaşma.
- **Stub** — Generated client class, RPC çağrılarını yapar.
- **Unary** — Tek request, tek response RPC tipi.
