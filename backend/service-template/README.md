# service-template — Lumix microservice iskeleti

Tüm Lumix backend servisleri bu iskeletten türer. **Hexagonal architecture**
(domain → application → adapter), Java 25, Spring Boot 4.1, Gradle (Kotlin DSL).

- Mimari referans: `documentation/docs/02-architecture-patterns/03-hexagonal-architecture.md`
- Spring/Java temeli: `documentation/docs/03-backend/01-spring-boot-foundation.md`,
  `documentation/docs/03-backend/02-java-25-virtual-threads.md`

## Modül yapısı

| Modül | Sorumluluk | İzinli bağımlılık |
|---|---|---|
| `domain` | Aggregate, VO, domain event, invariant. **Framework YOK.** | Sadece JDK |
| `application` | Use case (inbound port) + outbound port + service orkestrasyon | `domain` + Spring stereotypes |
| `adapter-rest` | Inbound HTTP (controller, DTO, RFC7807) | `application` |
| `adapter-grpc` | Inbound gRPC (`.proto` → codegen) | `application` |
| `adapter-kafka` | Inbound consumer + outbound event publisher | `application` |
| `adapter-persistence` | Outbound JPA repository + Flyway migration | `application` |
| `bootstrap` | `@SpringBootApplication`, config, `application.yml`, `bootJar` | tüm modüller |

Bağımlılık yönü **her zaman içeriye doğru**; `HexagonalArchitectureTest` (ArchUnit)
bunu CI'da otomatik denetler. `domain`/`application` içine adapter bağımlılığı sokarsan build kırmızı olur.

## Yeni servis nasıl türetilir (≈10 dakika)

Diyelim yeni servis `academic-service`, paket `com.lumix.academic`:

1. **Klasörü kopyala:**
   ```bash
   cp -r service-template academic-service
   ```
2. **Paketi yeniden adlandır** — `com/lumix/template` → `com/lumix/academic` (tüm modüllerde):
   ```bash
   cd academic-service
   grep -rl 'com.lumix.template' . | xargs sed -i 's/com\.lumix\.template/com.lumix.academic/g'
   find . -type d -path '*/com/lumix/template' | while read d; do
     git mv "$d" "$(dirname "$d")/academic"
   done
   ```
   `TemplateServiceApplication` → `AcademicServiceApplication` (dosya adı + `springBoot.mainClass`).
3. **`settings.gradle.kts`'e (backend kökü) modülleri ekle:**
   ```kotlin
   include(
     ":academic-service:domain",
     ":academic-service:application",
     ":academic-service:adapter-rest",
     ":academic-service:adapter-grpc",
     ":academic-service:adapter-kafka",
     ":academic-service:adapter-persistence",
     ":academic-service:bootstrap",
   )
   ```
   Modül içi `project(":service-template:...")` yollarını `:academic-service:...` yap.
4. **Örnek `Sample` dilimini kendi domain'inle değiştir** (aggregate, port, adapter, `.proto`,
   `V1__*.sql`, `SERVICE_NAME`/`DB_NAME`/topic isimleri).
5. **Derle:**
   ```bash
   ./gradlew :academic-service:bootstrap:build
   ```

## Komutlar

```bash
./gradlew check                    # derle + test + spotless + checkstyle
./gradlew spotlessApply            # formatı otomatik düzelt (commit öncesi)
./gradlew check -Pintegration      # Testcontainers testlerini de çalıştır (Docker gerekir)
./gradlew :service-template:bootstrap:bootRun   # lokal çalıştır (Postgres/Kafka gerekli)
./gradlew dependencyCheckAggregate # OWASP güvenlik taraması (NVD, ağ gerekir)
./gradlew sonar                    # SonarQube analizi (SONAR_HOST_URL/SONAR_TOKEN)
```

## Çalıştırma & health

Virtual threads aktif (`spring.threads.virtual.enabled=true`). Endpoint'ler:

- `GET /actuator/health` — genel sağlık
- `GET /actuator/health/liveness` — K8s liveness probe
- `GET /actuator/health/readiness` — K8s readiness probe (DB dahil)
- `GET /actuator/prometheus` — metrik scrape
- HTTP `:8080`, gRPC `:9090`

## Docker (distroless)

```bash
# backend/ kökünden:
docker build -f service-template/Dockerfile -t lumix/service-template:local .
```
Multi-stage: bootJar → jlink küçük JRE → `gcr.io/distroless/base-debian12`. Sonuç < 200 MB.

## Bilinçli şablon kararları / TODO'lar

- **Kafka publisher** doğrudan `send` yapıyor; production'da **Outbox pattern** kullan
  (`documentation/docs/02-architecture-patterns/06-outbox-pattern.md`).
- **gRPC** `net.devh` starter ile; ileride resmi **Spring gRPC**'ye geçiş planlı
  (`03-grpc-service-communication` §4.8). İlk gerçek build'de sürüm hizasını doğrula.
- **Güvenlik** (Spring Security / JWT) şablona dahil değil — servis bazında eklenir;
  eklerken `/actuator/health/**` ve `/actuator/prometheus` erişimini `permitAll` yap.
- **jlink modül seti** (Dockerfile) cömert seçildi; runtime `NoClassDefFoundError` olursa eksik modülü ekle.
