---
title: "4 · Konfigürasyon & Çalıştırma"
description: "application.yml satır satır, profiller, JSON loglama, actuator/health, Dockerfile aşamaları ve servisi lokalde ayağa kaldırma."
sidebar_position: 4
---

# Konfigürasyon & Çalıştırma

## Bu sayfa ne anlatıyor?

`bootstrap/src/main/resources/` altındaki konfigürasyon dosyalarını satır satır
anlayacak, profillerin nasıl seçildiğini öğrenecek ve servisi kendi makinende
çalıştırabilir hâle geleceksin. Son bölümde Docker imajının üç aşaması var.

## 1. Konfigürasyon felsefesi: koda değil, ortama bağla

Aynı `app.jar` dev'de, staging'de ve üretimde çalışmalı. Bu yüzden **hiçbir ortam
bilgisi koda gömülmez**; her şey iki mekanizmadan gelir:

1. **Environment variable + varsayılan** kalıbı: `${DB_HOST:localhost}` =
   "`DB_HOST` env değişkeni varsa onu, yoksa `localhost` kullan". Lokalde hiçbir şey
   ayarlamadan çalışır; K8s'te env/Secret ile ezilir.
2. **Profil dosyaları**: `application.yml` (ortak taban) + `application-<profil>.yml`
   (ortama özgü farklar). Aktif profil `SPRING_PROFILES_ACTIVE` ile seçilir
   (varsayılan: `dev`).

**Sır kuralı:** parola/token asla yml'e yazılmaz — yalnızca env/K8s Secret.
Yanlışlıkla commit'i `pre-commit` hook'u ve `.gitignore` (`.env`, `*.pem`...) engeller.

## 2. `application.yml` — ortak taban, satır satır

```yaml
spring:
  application:
    name: ${SERVICE_NAME:service-template}   # metriklerde/loglarda görünen ad

  threads:
    virtual:
      enabled: true      # Java 25 virtual threads: her istek ucuz bir sanal thread'de.
                         # Bloklanan I/O (DB, HTTP) OS thread'i kilitlemez.

  datasource:            # Runtime (DML) → PgBouncer transaction pooling (6432)
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:6432}/${DB_NAME:template_db}
    username: ${DB_USER:template_app}        # runtime app kullanıcısı (yalnızca DML)
    password: ${DB_PASSWORD:template}        # gerçek ortamda Secret'tan gelir
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:10}  # havuzdaki maks. bağlantı
      leak-detection-threshold: 30000        # kapatılmayan bağlantı uyarısı
      data-source-properties:
        prepareThreshold: 0                  # PgBouncer transaction mode: server-side prepared stmt KAPALI

  jpa:
    hibernate:
      ddl-auto: validate    # ŞEMANIN SAHİBİ FLYWAY'DİR. Hibernate şema ÜRETMEZ,
                            # yalnızca entity ↔ tablo uyumunu doğrular. Uyumsuzsa
                            # açılışta patlar — sessiz drift'ten iyidir.
    open-in-view: false     # OSIV kapalı: lazy-loading view'a sızamaz (N+1 tuzağı)

  flyway:
    enabled: true
    locations: classpath:db/migration   # V1__*.sql dosyalarının yeri
    # Migration = DDL → ayrı <svc>_migrator kullanıcısı + doğrudan Postgres (5432), PgBouncer BYPASS.
    # (spring.flyway.url dev/prod profilinde 5432/<svc>_migrator'a bakar; bkz. DB-per-service dokümanı.)

  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
    producer:
      acks: all                          # tüm replikalar onaylamadan "gönderildi" deme
      properties:
        enable.idempotence: true         # retry'da çift kayıt üretme
    consumer:
      enable-auto-commit: false          # offset'i biz yönetiriz (işlemeden commit yok)
      isolation-level: read_committed    # transaction'lı producer'larla uyum

server:
  port: ${SERVER_PORT:8080}
  shutdown: graceful       # SIGTERM'de aktif istekler bitene kadar bekle (K8s rollout)

grpc:
  server:
    port: ${GRPC_PORT:9090}   # net.devh gRPC server ayrı portta

management:                 # Actuator: sağlık + metrik uçları
  endpoints:
    web:
      exposure:
        include: health, info, prometheus   # yalnızca bu üçü dışarı açık
  endpoint:
    health:
      probes:
        enabled: true       # /actuator/health/liveness ve /readiness üretilir
      group:
        readiness:
          include: readinessState, db   # DB kapalıysa readiness DOWN → pod trafik almaz

lumix:                      # bize özel ayarlar → KafkaTopicsProperties record'una bağlanır
  kafka:
    domain-events-topic: ${DOMAIN_EVENTS_TOPIC:sample.domain-events}
    inbound-topic: ${INBOUND_TOPIC:sample.commands}
```

`lumix.kafka.*` gibi kendi ayar bloklarını `@ConfigurationProperties` record'larıyla
tip-güvenli okuyoruz ([nasıl?](03-service-template-turu.md)) — dağınık `@Value` yerine
tek sınıfta toplanır, yazım hatası açılışta yakalanır.

## 3. Profil dosyaları — farklar tablosu

| Ayar | `dev` | `test` | `prod` |
|---|---|---|---|
| SQL logu | `show-sql: true` + formatlı | — | kapalı |
| Log seviyesi | `com.lumix: DEBUG` | `com.lumix: DEBUG` | `root: WARN`, `com.lumix: INFO` |
| Health detayı | `always` (her şeyi göster) | — | `when-authorized` |
| Hikari havuzu | 10 (taban) | — | max 30 / min idle 10 |
| DB bağlantısı | localhost varsayılanları | **Testcontainers enjekte eder** | tamamı env/Secret |

- `application-test.yml` entegrasyon testinde kullanılır: `SmokeIntegrationTest`,
  `@DynamicPropertySource` ile Testcontainers'ın rastgele portlu Postgres'ini enjekte eder.
- Profil seçimi: lokalde hiçbir şey yapma (`dev` varsayılan); başka profil için
  `SPRING_PROFILES_ACTIVE=prod` env'i.

## 4. `logback-spring.xml` — iki kişilikli loglama

```
dev profili   → insan-okunur:  14:23:05.123 INFO  [corrId] c.l.t.a.SampleController - ...
diğer hepsi   → tek satır JSON: {"@timestamp":"...","message":"...","service":"...","correlationId":"..."}
```

Neden JSON? Üretimde logları makine okur (ELK/Loki): alan bazlı arama
(`tenantId=X olan hatalar`) ancak yapılandırılmış logla mümkün. `correlationId` ve
`tenantId` MDC alanları otomatik dahil edilir — ileriki sprintlerde bir isteğin tüm
servislerdeki izini bu ID ile süreceğiz (bkz. Observability & QA bölümündeki
"Observability: Log, Metric, Trace ve Correlation ID" sayfası).

## 5. Actuator uçları — servisin nabzı

| Uç | Kim kullanır | Ne söyler |
|---|---|---|
| `GET /actuator/health` | insan/genel | genel durum |
| `GET /actuator/health/liveness` | K8s liveness probe | "süreç canlı mı?" — DOWN ise pod yeniden başlatılır |
| `GET /actuator/health/readiness` | K8s readiness probe | "trafik alabilir mi?" — **DB dahil**; DB yoksa trafik kesilir ama pod öldürülmez |
| `GET /actuator/prometheus` | Prometheus scrape | metrikler (JVM, HTTP, Hikari, Kafka) |

Liveness ↔ readiness ayrımı kritiktir: DB kesintisinde pod'u öldürmek (liveness'a DB
koymak) hiçbir şeyi çözmez; trafiği kesmek (readiness) doğru davranıştır.

## 6. Servisi lokalde çalıştırma

Ön koşul: Docker (Postgres/Kafka için) + JDK 25 (yoksa Gradle indirir).

```bash
# 1) PostgreSQL + PgBouncer (per-service DB'ler + template_db, migrator/app kullanıcıları):
( cd campus/infra/postgres && cp -n .env.example .env && docker compose --env-file .env up -d )
#    İlk açılışta 12 lumix_<servis> DB + template_db provizyonlanır (runtime 6432, migration 5432).
#    Doğrulama (opsiyonel): bash campus/infra/postgres/verify.sh
docker run -d --name lumix-kafka -p 9092:9092 apache/kafka:3.9.0   # KRaft, tek node

# 2) Servisi başlat (dev profili varsayılan):
cd campus/backend
./gradlew :service-template:bootstrap:bootRun

# 3) Doğrula:
curl localhost:8080/actuator/health/readiness          # {"status":"UP"}
curl -X POST localhost:8080/api/v1/samples \
  -H "Content-Type: application/json" -d '{"name":"deneme"}'   # 201 + JSON
```

Kafka'sız da açılır (publisher ilk `send`'de hata loglar) — hızlı REST denemesi için
Postgres yeterli. Tam doğrulama her zaman: `./gradlew check -Pintegration`.

## 7. `Dockerfile` — üç aşamalı distroless imaj

```dockerfile
# Aşama 1: derle — tam JDK'lı imajda ./gradlew bootJar → app.jar
FROM eclipse-temurin:25-jdk AS build
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./   # önce build tanımları
COPY gradle ./gradle                                                     # (cache katmanı!)
COPY service-template ./service-template
RUN ./gradlew --no-daemon :service-template:bootstrap:bootJar

# Aşama 2: jlink — uygulamanın kullandığı JDK modüllerinden KÜÇÜK bir JRE üret
FROM eclipse-temurin:25-jdk AS jre-build
RUN "$JAVA_HOME/bin/jlink" --add-modules java.base,java.sql,... --output /javaruntime

# Aşama 3: distroless çalışma imajı — shell yok, paket yöneticisi yok, root yok
FROM gcr.io/distroless/base-debian12:nonroot
COPY --from=jre-build /javaruntime /opt/java/jre
COPY --from=build /workspace/service-template/bootstrap/build/libs/app.jar /app/app.jar
ENTRYPOINT ["/opt/java/jre/bin/java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
```

Neden bu üç aşama?

- **Katman cache'i:** build tanımları kaynak koddan önce COPY edilir — kod değişince
  bağımlılık indirme katmanı cache'ten gelir, imaj hızlı döner.
- **jlink:** tam JDK ~300 MB; uygulamanın gerçekten kullandığı modüllerden üretilen
  JRE çok daha küçük. Sonuç imaj < 200 MB.
- **Distroless:** son imajda shell bile yok → saldırı yüzeyi minimum. Bedeli:
  `kubectl exec ... bash` çalışmaz; sağlık kontrolleri bu yüzden K8s `httpGet`
  probe'larıyla yapılır (`curl` da yok!).
- **`MaxRAMPercentage=75`:** heap, container memory limitinin %75'i olur — limit
  değişince JVM ayarı elle güncellenmez.

```bash
# İmajı lokalde denemek (backend/ kökünden):
docker build -f service-template/Dockerfile -t lumix/service-template:local .
```

CI'da aynı Dockerfile Kaniko ile build edilip GitLab Container Registry'ye push edilir.

## 8. Tuzaklar

- **Migration'ı düzenleme, yenisini ekle** — uygulanmış `V*.sql` değiştirilirse Flyway
  checksum hatasıyla açılmaz.
- **`ddl-auto: validate`'i asla `update` yapma** — Hibernate'e şema ürettirmek,
  Flyway geçmişiyle çatallanan kontrolsüz şema demektir.
- **Actuator'a yeni uç açarken düşün** — `exposure.include` bilinçli olarak kısıtlı;
  `env`, `heapdump` gibi uçlar hassas bilgi sızdırır.
- **Sır yml'e yazılmaz** — `${VAR:default}` kalıbındaki default yalnızca lokal
  kolaylıktır; gerçek değer her ortamda env/Secret'tan gelmeli.

## 9. Sonraki adım

[Kalite Güvencesi & Git](05-kalite-guvence-ve-git.md) — kodun format/analiz/test
kapıları ve commit'ten merge'e otomasyon zinciri.
