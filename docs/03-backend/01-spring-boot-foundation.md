---
title: Spring Boot Foundation
description: Spring Boot 3.6, auto-configuration, profile yönetimi, config externalization ve Lumix'te kullanılan application.yml convention.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Bu sayfa Lumix'in backend temel taşı **Spring Boot 3.6**'in ne sunduğunu, **auto-configuration**'ın nasıl çalıştığını, **profile** mekanizmasının Lumix'te dev/staging/prod ayrımını nasıl yönettiğini ve **config externalization** (environment variable, K8s ConfigMap/Secret) ile yapılandırmanın nasıl dışsallaştırıldığını anlatıyor. Sonunda yeni gelen geliştirici bir microservice'in başlangıç yapısını, `application.yml` standardını, profile'ı ve cluster'da nasıl çalıştığını anlıyor olmalı.

## 1. Bu nedir? (Sıfırdan)

**Spring Boot**, Spring Framework üzerine kurulu, **"opinionated"** (önceden tercihli) bir Java başlangıç şablonu. 2014'te 1.0 sürümü çıktı. Vaadi: "Java web uygulaması başlatmak için XML yazma, classpath dert etme, embedded server kur — sadece kodla başla."

**Günlük hayattan analoji:**
Bir ev kuruyorsun. İki seçenek:
- **Seçenek 1 — Sıfırdan inşa (saf Spring):** temeli, duvarı, elektriği, suyu, çatıyı tek tek planla. Her şey senin kontrolünde ama 2 ay sürüyor.
- **Seçenek 2 — Prefabrik ev (Spring Boot):** üretici ev modüllerini hazır verir. Elektrik prizleri standart, su tesisatı bağlı, ısıtma sistemi entegre. Tek yapacağın mobilyaları yerleştirmek. 2 günde taşınılır hale gelir.

Spring Boot ikincidir. **Default'lar makul**, **gerek görmedikçe override etmiyorsun**.

### Spring Boot ne sağlar?

1. **Auto-configuration** — classpath'ta hangi kütüphaneler varsa onlara göre otomatik bean yaratır
2. **Embedded server** — Tomcat/Jetty/Undertow JAR içinde gömülü, `java -jar` ile çalışır
3. **Starter dependency'ler** — `spring-boot-starter-web`, `spring-boot-starter-data-jpa` gibi gruplandırılmış paketler
4. **Externalized configuration** — `application.yml`, environment variable, command-line arg sıralı
5. **Production-ready features** — Actuator (health, metric, info endpoint), graceful shutdown
6. **Opinionated defaults** — JSON için Jackson, web için Tomcat, security için filter chain

### Spring Boot 3.6 — ne yeni?

- **Java 17 minimum** (Lumix Java 25 kullanıyor)
- **Jakarta EE 10** (javax → jakarta paket geçişi)
- **Native image** desteği (GraalVM, Lumix kullanmıyor şimdilik)
- **Observability** (Micrometer + OpenTelemetry hazır)
- **Virtual threads** Spring entegrasyonu (Java 25 ile)
- **Spring Security 6.x**, **Spring Data JPA 3.x**
- LTS desteği 2025 sonuna kadar (commercial Tanzu support daha uzun)

## 2. Hangi problemi çözüyor?

Spring Boot olmadan klasik Spring uygulaması kurmak demek:

**Acı 1 — XML cehennemi.**
`applicationContext.xml`, `dispatcher-servlet.xml`, `web.xml`, `persistence.xml` — 500 satır XML, bean declaration'lar, scope tanımları.

**Acı 2 — Classpath cehennemi.**
Spring MVC + Hibernate + Jackson eşleşmesinde version uyumsuzluğu, "NoClassDefFoundError" zamanın yarısını alır.

**Acı 3 — Server konfigürasyonu.**
Tomcat ayrı kurulması gerekir, WAR build edilir, deploy edilir. CI/CD ekstra adımlar.

**Acı 4 — Production'a hazırlık.**
Health check, metric, log ayarları — her şey elle eklenir.

**Acı 5 — "Hangi versiyonu kullanayım?"**
Spring 5.3, Hibernate 5.6, Jackson 2.13 mü 2.14 mü? Saatler harcanır.

**Acı 6 — Config yönetimi.**
"Dev'de bu DB, prod'da şu DB" ayrımını yapmak için properties file'ları, system property'ler, JNDI...

Spring Boot bu acıları şöyle çözer:

| Acı | Boot çözümü |
|---|---|
| XML cehennemi | Java config + auto-configuration (XML opsiyonel) |
| Classpath cehennemi | `spring-boot-dependencies` BOM tüm sürümleri pin'ler |
| Server konfigürasyonu | Embedded Tomcat, `java -jar` ile çalışır |
| Production hazırlığı | Actuator out-of-the-box health/metric/info endpoint'leri |
| "Hangi versiyon?" | Boot versiyon seç, geri kalanı uyumlu |
| Config yönetimi | Profile + externalized config + environment variable |

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Auto-configuration

Boot'un sihrinin temeli `@EnableAutoConfiguration` annotation'ıdır (genelde `@SpringBootApplication` içinde).

Mekanizma:
1. Boot uygulaması başlar
2. Classpath taranır, hangi kütüphaneler var?
3. `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` dosyasındaki sınıflar yüklenir
4. Her auto-configuration sınıfı `@ConditionalOn...` annotation'ları içerir (örn. `@ConditionalOnClass(DataSource.class)`)
5. Koşul sağlanırsa bean'ler yaratılır

Örnek: `DataSourceAutoConfiguration`:
- Classpath'ta JDBC sürücüsü varsa
- `spring.datasource.url` config'i varsa
- DataSource bean yaratılır

Sen `@Bean DataSource ...` yazmadın — Boot yaptı.

**Auto-config'i override etmek:**
Kendi `@Bean DataSource dataSource()` tanımladığında, `@ConditionalOnMissingBean(DataSource.class)` yüzünden Boot kendi tanımını atlar. **Esnek**.

### 3.2. Spring application başlangıcı

```java
@SpringBootApplication
public class AcademicServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AcademicServiceApplication.class, args);
    }
}
```

Bu yedi satır yapar:
1. `@ComponentScan` — `com.lumix.academic.*` paketini tara
2. `@EnableAutoConfiguration` — classpath'a göre auto-config çalıştır
3. `@Configuration` — bu sınıf da config olarak değerlendirilir
4. SpringApplication context'i oluştur
5. Embedded server'ı başlat (port 8080)
6. Actuator endpoint'leri ekle (varsa)
7. Application up.

### 3.3. Configuration sırası (sırasıyla okunur)

Spring Boot config'i şu sırayla okur (en alttaki en üstün):

1. `application.yml` / `application.properties` (jar içinde)
2. `application-{profile}.yml` (jar içinde)
3. Jar'ın yanında `application.yml`
4. Jar'ın yanında `application-{profile}.yml`
5. Environment variable (`MY_KEY` veya `MY_PROP`)
6. System property (`-Dmy.prop=value`)
7. Command-line arg (`--my.prop=value`)
8. SPRING_APPLICATION_JSON

Yani: command-line arg → env var → file. Production'da genelde **environment variable** ile config edilir (K8s ConfigMap/Secret).

### 3.4. Profiles

Profile = "config grup". Lumix'te:
- `dev` — local development (Docker compose, Tilt)
- `test` — CI'da unit/integration test
- `staging` — staging cluster
- `prod` — production

Profile etkin yapmak:
```bash
SPRING_PROFILES_ACTIVE=prod java -jar academic-service.jar
```

Profile-specific file: `application-prod.yml`. Default `application.yml` ile birleşir, prod override eder.

### 3.5. Externalized configuration örneği

```yaml
# application.yml (default — paylaşılan değerler)
spring:
  application:
    name: academic-service

server:
  port: 8080
  shutdown: graceful

management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus
```

```yaml
# application-prod.yml (prod-specific)
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:5432/academic_db
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  jpa:
    hibernate:
      ddl-auto: validate

logging:
  level:
    com.lumix: INFO
    org.springframework: WARN
```

`${DB_HOST}` = environment variable. K8s'te ConfigMap/Secret'tan inject edilir.

### 3.6. Configuration property class

Type-safe config için:

```java
@ConfigurationProperties(prefix = "lumix.attendance")
public record AttendanceProperties(
    Duration revisionWindow,
    int maxStudentsPerClass,
    boolean strictMode
) {}
```

YAML'da:
```yaml
lumix:
  attendance:
    revision-window: PT24H   # ISO 8601 duration
    max-students-per-class: 30
    strict-mode: true
```

Inject:
```java
@Service
@RequiredArgsConstructor
public class MarkAttendanceService {
    private final AttendanceProperties props;
    // props.revisionWindow() ile erişim
}
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Spring Boot 3.6 + Java 25 LTS

| Karar | Sebep |
|---|---|
| Spring Boot 3.6 | LTS, Java 25 destekli, 2025'e kadar destek, takım deneyimi |
| Java 25 | LTS (2028 destek), virtual threads, modern Java |
| Embedded Tomcat | Default seçim, K8s native (sidecar gerek yok) |
| Jakarta EE 10 | `javax.*` → `jakarta.*` geçişi tamamlandı |

Detay: [Java 25 Virtual Threads](./02-java-25-virtual-threads), [Teknoloji Kararları](../00-overview/02-technology-stack-decisions).

### 4.2. Lumix standart `application.yml`

Her servis aynı şablonu izler:

```yaml
# src/main/resources/application.yml
spring:
  application:
    name: ${SERVICE_NAME:academic-service}
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
    group:
      prod: prod, observability-prod
      staging: staging, observability-staging
      dev: dev, observability-dev

  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:academic_db}
    username: ${DB_USER:academic}
    password: ${DB_PASSWORD:academic}
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:10}
      minimum-idle: ${DB_POOL_MIN_IDLE:2}
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000

  jpa:
    hibernate:
      ddl-auto: validate  # Flyway sorumlu, JPA otomatik DDL yapmıyor
    properties:
      hibernate:
        jdbc:
          time_zone: UTC

  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: public
    baseline-on-migrate: true

  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
    properties:
      schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8080/apis/registry/v2}
    producer:
      acks: all
      enable-idempotence: true
      compression-type: snappy
    consumer:
      enable-auto-commit: false
      isolation-level: read_committed

  threads:
    virtual:
      enabled: true   # Java 25 virtual threads

server:
  port: ${SERVER_PORT:8080}
  shutdown: graceful
  forward-headers-strategy: framework
  tomcat:
    threads:
      max: 200
      min-spare: 10

grpc:
  server:
    port: ${GRPC_PORT:9090}

management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, threaddump
      base-path: /actuator
  endpoint:
    health:
      show-details: when-authorized
      probes:
        enabled: true   # K8s liveness/readiness probe support
  metrics:
    tags:
      service: ${spring.application.name}
      environment: ${SPRING_PROFILES_ACTIVE:dev}

logging:
  pattern:
    level: "%X{correlationId:-} %X{tenantId:-} %5p"
  level:
    root: INFO
    com.lumix: INFO

lumix:
  outbox:
    relay:
      batch-size: 100
      interval-ms: 500
    cleanup:
      retention-days: 7
```

### 4.3. Profile dosyaları

**`application-dev.yml`** — local development:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/academic_db
    username: academic
    password: academic
  jpa:
    show-sql: true
    properties:
      hibernate:
        format_sql: true
  kafka:
    bootstrap-servers: localhost:9092

logging:
  level:
    com.lumix: DEBUG
    org.springframework.web: DEBUG

management:
  endpoint:
    health:
      show-details: always
```

**`application-prod.yml`** — production:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30
      minimum-idle: 10
  jpa:
    show-sql: false

logging:
  level:
    root: WARN
    com.lumix: INFO

management:
  endpoint:
    health:
      show-details: when-authorized
```

### 4.4. K8s entegrasyonu — environment variable inject

ConfigMap (non-sensitive):

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: academic-service-config
data:
  SPRING_PROFILES_ACTIVE: "prod"
  DB_HOST: "academic-postgres.svc.cluster.local"
  KAFKA_BROKERS: "kafka-0.kafka:9092,kafka-1.kafka:9092"
  SCHEMA_REGISTRY_URL: "http://apicurio.shared:8080/apis/registry/v2"
```

Secret (sensitive — Vault sync):

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: academic-db-credentials
type: Opaque
stringData:
  DB_USER: "academic"
  DB_PASSWORD: "<vault-injected>"
```

Deployment:

```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
      - name: academic-service
        image: registry.lumix.io/academic-service:2.3.1
        envFrom:
        - configMapRef:
            name: academic-service-config
        - secretRef:
            name: academic-db-credentials
        ports:
        - containerPort: 8080
        - containerPort: 9090   # gRPC
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
        resources:
          requests:
            cpu: "200m"
            memory: "512Mi"
          limits:
            cpu: "1000m"
            memory: "1Gi"
```

### 4.5. Actuator endpoint'leri

Lumix'te enable edilenler:
- `/actuator/health` — basic health
- `/actuator/health/liveness` — K8s liveness probe
- `/actuator/health/readiness` — K8s readiness probe
- `/actuator/info` — build info
- `/actuator/prometheus` — metric scrape endpoint
- `/actuator/threaddump` — debug için (authorize edilmiş)

Diğerleri (`/env`, `/beans`, `/configprops`) **production'da kapalı** — bilgi sızdırma riski.

### 4.6. Configuration property kullanım örneği

```java
@ConfigurationProperties(prefix = "lumix.outbox.relay")
public record OutboxRelayProperties(
    @Positive int batchSize,
    @Positive Duration interval
) {
    public OutboxRelayProperties {
        if (batchSize > 1000) {
            throw new IllegalArgumentException("Batch size 1000'den fazla olamaz");
        }
    }
}

@Configuration
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxConfig {}

@Component
@RequiredArgsConstructor
public class OutboxKafkaRelay {
    private final OutboxRelayProperties props;

    @Scheduled(fixedDelayString = "#{@outboxRelayProperties.interval.toMillis()}")
    public void relay() {
        // ... props.batchSize() kullanımı
    }
}
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

**Alternatif 1 — Quarkus**
Red Hat'in cloud-native Java framework'ü, GraalVM native imaja optimize.

Niye elendi:
- Spring ekosistemi takımda daha yaygın
- Spring Security, Spring Data, Spring Kafka olgun
- Quarkus daha küçük topluluk, daha az enterprise olgunluk

**Alternatif 2 — Micronaut**
Reflection-az framework, hızlı startup.

Niye elendi:
- Yine Spring ekosistemi daha geniş
- Olgun starter'lar daha az

**Alternatif 3 — Helidon, Vert.x**
Reactive odaklı.

Niye elendi:
- Lumix'in çoğu use case'i blocking — Java 25 virtual threads ile imperative kod yeter
- Reactive complexity bedava değil

**Alternatif 4 — Plain Spring (Boot olmadan)**
XML config, dış Tomcat.

Niye elendi:
- 2010'lar tarzı, gereksiz boilerplate
- Yeni geliştirici eğitimi zor

### 5.2. Spring Boot 3.x mı 2.x mi?

3.x avantajları (seçildi):
- Java 17+ baseline → modern Java
- Jakarta EE 10 (gelecek uyumlu)
- Native image desteği (opsiyon)
- Virtual threads (Java 25 ile birlikte)
- Yeni observability stack (Micrometer + OTel)

2.x dezavantajları:
- Java 8 support → eski Java zorunlu kalır
- `javax.*` paket isimleri → uzun vadede deprecated

### 5.3. Tomcat mı Undertow mu Jetty mi?

Lumix: **Tomcat** (default).

| Server | Karar |
|---|---|
| Tomcat | **Seçildi** — default, geniş test edilmiş |
| Undertow | Daha az memory, ama topluluk küçük |
| Jetty | Esnek ama default değil |

Performans farkı %5-10 — Java 25 virtual threads ile zaten throughput artıyor, embedded server seçimi kritik değil.

### 5.4. Kabul edilen trade-off'lar

| Trade-off | Maliyet | Mitigation |
|---|---|---|
| Auto-config sihri | "Neden bu bean oluştu?" debug zor | `--debug` flag ile auto-config raporu |
| Jar size | 50-100 MB | OCI image base layer cache |
| Startup time | 5-15 saniye | K8s readinessProbe initialDelay |
| Memory footprint | 256-512 MB minimum | Heap tuning, JVM flags |
| Lock-in to Spring | Mimari Spring'e bağlı | Hexagonal + port/adapter ile core izole |

## 6. Pratik örnek

### 6.1. Minimal microservice — sıfırdan başlangıç

**build.gradle.kts:**

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.lumix"
version = "2.3.1"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.flywaydb:flyway-core")
    implementation("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    // gRPC starter (net.devh — bkz. gRPC sayfası 4.8: Spring gRPC geçiş notu)
    implementation("net.devh:grpc-server-spring-boot-starter:3.1.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:postgresql")
}
```

> gRPC/Protobuf codegen için `com.google.protobuf` protobuf-gradle-plugin ayrıca eklenir — bkz. [gRPC Service Communication §4.3](./03-grpc-service-communication). `spring-boot-maven-plugin`'in karşılığı `org.springframework.boot` Gradle plugin'idir (`bootJar` task'ı sağlar).

**Application class:**

```java
package com.lumix.academic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan("com.lumix.academic.config")
@EnableAsync
@EnableScheduling
public class AcademicServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AcademicServiceApplication.class, args);
    }
}
```

### 6.2. Profile-bound bean

```java
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    @Bean
    public SecurityFilterChain devSecurityChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .build();
    }
}

@Configuration
@Profile("!dev")  // prod, staging, test
public class ProdSecurityConfig {

    @Bean
    public SecurityFilterChain prodSecurityChain(HttpSecurity http, JwtAuthenticationConverter jwtConverter) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/actuator/prometheus").hasRole("PROMETHEUS")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
            .build();
    }
}
```

### 6.3. ConfigurationProperties — type-safe config

```java
// config/AttendanceProperties.java
@ConfigurationProperties(prefix = "lumix.attendance")
@Validated
public record AttendanceProperties(
    @NotNull Duration revisionWindow,
    @Positive int maxStudentsPerClass,
    boolean strictMode
) {}
```

**application.yml:**
```yaml
lumix:
  attendance:
    revision-window: PT24H
    max-students-per-class: 30
    strict-mode: true
```

**Kullanım:**
```java
@Service
@RequiredArgsConstructor
public class MarkAttendanceService {
    private final AttendanceProperties props;

    public AttendanceId execute(MarkAttendanceCommand cmd) {
        if (cmd.marks().size() > props.maxStudentsPerClass()) {
            throw new ClassCapacityExceededException();
        }
        // ...
    }
}
```

### 6.4. Custom health check

```java
@Component
@RequiredArgsConstructor
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaAdmin kafkaAdmin;

    @Override
    public Health health() {
        try (AdminClient client = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            DescribeClusterResult result = client.describeCluster();
            int nodeCount = result.nodes().get(3, TimeUnit.SECONDS).size();
            if (nodeCount == 0) {
                return Health.down().withDetail("brokers", 0).build();
            }
            return Health.up().withDetail("brokers", nodeCount).build();
        } catch (Exception ex) {
            return Health.down(ex).build();
        }
    }
}
```

K8s readiness probe `/actuator/health/readiness`'a hit eder; Kafka down ise pod traffic almaz.

### 6.5. Graceful shutdown

```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

K8s `kubectl delete pod` yaptığında:
1. SIGTERM gönderir
2. Tomcat yeni request kabul etmez
3. Devam eden request'ler bitirilir (30s'ye kadar)
4. Pod kapanır

Bu Kafka consumer için de geçerli: in-flight message'lar tamamlanır.

### 6.6. Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-jammy

ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"

EXPOSE 8080 9090

ENTRYPOINT exec java $JAVA_OPTS -jar /app.jar
```

`-XX:MaxRAMPercentage=75.0` — JVM, container memory limit'inin %75'ini heap olarak kullanır (kalan %25 metaspace, threads, JIT için).

## 7. Dikkat edilecek tuzaklar

**Tuzak 1 — Auto-config'i fazla override etmek.**
Spring Boot'un getirdiği default'lara güvenmek yerine her şeyi elle yazılır. Boilerplate patlar.
**Önleme:** Default'u kabul et, sadece gerçekten farklı davranış lazımsa override.

**Tuzak 2 — `application.yml`'a secret yazmak.**
DB password, API key plain text. Git'e push edildi, vault'tan önce.
**Önleme:** Secret'lar environment variable veya Vault'tan. `.yml` sadece placeholder.

**Tuzak 3 — Production'da `show-sql: true`.**
Hibernate her query'yi log'lar. Log volume patlar, performans düşer.
**Önleme:** Profile-specific. `prod`'da `false`, `dev`'de `true`.

**Tuzak 4 — Actuator'ı korumasız bırakmak.**
`/actuator/env` veya `/actuator/configprops` external erişime açık — secret leak.
**Önleme:** Actuator endpoint'leri Spring Security ile koruma. Sadece `/health/*` ve `/prometheus` public.

**Tuzak 5 — `ddl-auto: update` veya `create`.**
JPA otomatik şema değişir. Production'da catastrophic.
**Önleme:** Lumix'te `ddl-auto: validate`. Şema değişimi Flyway ile.

**Tuzak 6 — `spring.jpa.open-in-view: true` (default).**
Lazy loading'i view layer'da yapar. N+1 query saldırısı. Performans öldürür.
**Önleme:** `spring.jpa.open-in-view: false`. Lazy load'u service'te yap.

**Tuzak 7 — Profile çakışması.**
`SPRING_PROFILES_ACTIVE=prod,dev` — dev override eder prod'u. Beklenmedik davranış.
**Önleme:** Profile listesini net tut. Production'da sadece `prod`.

**Tuzak 8 — Healthcheck endpoint sızıntısı.**
`management.endpoint.health.show-details: always` — DB password vs. sızdırabilir.
**Önleme:** `when-authorized` veya `never`. Authorize edilmiş kullanıcıya detail.

**Tuzak 9 — Tomcat thread pool dar.**
Default 200, blocking workload için yetersiz olabilir.
**Önleme:** Java 25 virtual threads enable. Veya `server.tomcat.threads.max` ayarla.

**Tuzak 10 — Boot version mismatch.**
Spring Kafka 3.0.x, Spring Boot 3.6.x ile uyumsuz olabilir. Manual version pin yapma.
**Önleme:** Boot BOM'a güven, sürüm pin'leme.

**Tuzak 11 — Embedded server'ı kapatmak.**
"Standalone değil, dış Tomcat istiyoruz" → WAR build et, web-app olarak deploy et. Boot avantajları kaybolur.
**Önleme:** Embedded server kullan, K8s'te native çalışıyor.

**Tuzak 12 — `@Value` aşırı kullanımı.**
50 yerde `@Value("${...}")` — bakımı zor, type-safe değil.
**Önleme:** `@ConfigurationProperties` ile grupla, type-safe yap.

## 8. Diğer konularla ilişkisi

- [Java 25 Virtual Threads](./02-java-25-virtual-threads) — Boot'la entegrasyon
- [gRPC Service Communication](./03-grpc-service-communication) — gRPC Spring Boot starter
- [Validation Strategy](./04-validation-strategy) — Spring `@Valid`
- [Error Handling RFC 7807](./05-error-handling-rfc7807) — Spring `@ControllerAdvice`
- [Microservices Architecture](../02-architecture-patterns/01-microservices-architecture) — her servis ayrı Boot uygulaması
- [Hexagonal Architecture](../02-architecture-patterns/03-hexagonal-architecture) — Spring annotation'ları adapter katmanında

## 9. Daha derine inmek için

**Resmi dokümantasyon:**
- docs.spring.io/spring-boot/docs/3.3.x/reference/html
- spring.io/projects/spring-boot
- spring.io/guides

**Kitap:**
- "Spring in Action, Sixth Edition" — Craig Walls
- "Spring Boot Up & Running" — Mark Heckler
- "Cloud Native Spring in Action" — Thomas Vitale

**Search keywords:**
- "spring boot 3 java 21 virtual threads"
- "spring boot auto configuration"
- "spring boot externalized configuration"
- "spring profile best practices"
- "spring boot kubernetes deployment"
- "spring boot graceful shutdown"
- "spring configurationproperties record"

## 10. Sözlük

- **Auto-configuration** — Boot'un classpath'a göre otomatik bean yaratması.
- **Actuator** — Boot'un health, metric, info gibi production endpoint'lerini sağlayan modül.
- **Application Context** — Spring'in IoC container'ı, bean'lerin yaşadığı yer.
- **Bean** — Spring'in yönettiği nesne (singleton, prototype vs.).
- **ConfigMap** — K8s'te non-sensitive config'i tutan obje.
- **ConfigurationProperties** — Type-safe config binding mekanizması.
- **Embedded Server** — JAR içine gömülü web server (Tomcat/Jetty/Undertow).
- **Externalized Configuration** — Config'in kod dışında (dosya, env var, system property) tutulması.
- **HikariCP** — Spring Boot default JDBC connection pool.
- **Profile** — Config grup ismi (`dev`, `prod`, `staging`).
- **Secret** — K8s'te sensitive data (password, key) tutan obje. Vault sync.
- **Starter** — Boot dependency grubu (`spring-boot-starter-web`, `-data-jpa` vs.).
