---
title: "Spring Modulith Testleri, Testcontainers ve Migration Testleri"
description: Spring Modulith ve Testcontainers ile module boundary, PostgreSQL integration ve Flyway migration davranışı testleri.
sidebar_position: 5
---

## Giriş

Module boundary ve database migration uzun vadeli maintainability kontrolleridir. Otomatik test edilmezlerse fark edilmeden bozulmaları kolaydır.

Spring Modulith application module structure doğrulayabilir ve module-scoped integration test çalıştırabilir. Testcontainers gerçek PostgreSQL infrastructure sağlayarak integration ve migration testlerini üretime daha yakın hale getirir.

## Neden Önemli

Modular sistemlerde regression çoğu zaman mimaridir:

- bir module başka module'ün internal alanına bağımlı olur,
- event listener beklenen event'i alamaz,
- migration boş DB'de çalışır ama mevcut data'da patlar,
- repository H2 ile çalışır ama PostgreSQL'de bozulur,
- Flyway ordering problemi sadece CI veya production'da görünür.

Module ve migration testleri bu hataları erken yakalar.

## Temel Kavramlar

- Spring Modulith: modular monolith uygulamalarını yapılandırma ve doğrulama için Spring projesi.
- `@ApplicationModuleTest`: module-scoped integration test desteği.
- Application module: uygulama içindeki domain odaklı module boundary.
- Testcontainers: testlerde disposable gerçek dependency başlatma library'si.
- PostgreSQL container: integration test için kullanılan gerçek PostgreSQL instance.
- Flyway migration test: migration'ların gerçek database üzerinde uygulanmasını test eder.
- Boundary verification: module dependency'lerinin izinli kurallara uyduğunu doğrular.

## Gerçek Ürün Geliştirmede Problem

Klasik integration testler çoğu zaman her senaryo için tüm uygulamayı ayağa kaldırır. Bu geniş güven verir ama module coupling'i saklayabilir ve feedback'i yavaşlatır.

Daha iyi yaklaşım katmanlıdır:

- module boundary yapısal olarak doğrulanır,
- module davranışı mümkün olduğunca izolasyonda test edilir,
- persistence behavior için gerçek PostgreSQL kullanılır,
- migration gerçekçi schema/data state ile çalıştırılır.

## Yaklaşım / Tasarım

### Module Test Skeleton: Attendance Module

```java
@ApplicationModuleTest
class AttendanceModuleTests {

  @Test
  void marksAttendanceAndPublishesDomainEvent(Scenario scenario) {
    scenario.stimulate(() -> attendanceUseCase.markAttendance(command()))
      .andWaitForEventOfType(AttendanceMarkedEvent.class)
      .matching(event -> event.tenantId().equals(command().tenantId()))
      .toArriveAndVerify(event -> assertThat(event.classroomId()).isNotNull());
  }
}
```

Test niyeti:

- module izinli dependency'leriyle boot eder,
- use case module API üzerinden çağrılır,
- beklenen domain event yayınlanır,
- module internal sınırları korunur.

### Module Test Skeleton: Messaging Module

```java
@ApplicationModuleTest
class MessagingModuleTests {

  @MockitoBean
  NotificationPort notificationPort;

  @Test
  void sendsMessageWithinConversationBoundary(Scenario scenario) {
    scenario.stimulate(() -> messagingUseCase.sendMessage(command()))
      .andWaitForStateChange(() -> messageRepository.findById(command().messageId()))
      .andVerify(message -> assertThat(message).isPresent());
  }
}
```

Test niyeti:

- cross-module dependency uygun yerde mock'lanır,
- persistence state change gözlenir,
- module contract unrelated module boot etmeden test edilir.

### PostgreSQL Testcontainers Skeleton

```java
@Testcontainers
@SpringBootTest
class PostgreSqlIntegrationTests {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("app")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }
}
```

### Flyway Migration Test Yönü

```text
1. PostgreSQL container başlat.
2. Boş database üzerine tüm Flyway migration'ları uygula.
3. Representative tenant/user/domain data ekle.
4. Repository smoke test çalıştır.
5. Riskli migration için previous schema fixture restore et.
6. Yeni migration'ı uygula.
7. Backfill, constraint, index ve rollback varsayımlarını doğrula.
```

## Sektör Standardı / Best Practice

- Module boundary'leri sadece behavior değil architecture olarak test et.
- SQL, index, constraint ve migration testleri için gerçek PostgreSQL tercih et.
- PostgreSQL-specific davranış için H2'yi substitute olarak kullanma.
- Module testleri exposed use case ve event'lere odakla.
- CI içinde stable image version ile Testcontainers kullan.
- Migration'ı sadece empty schema değil representative data ile test et.
- Migration testlerini zero-downtime kurallarına bağla.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
module_test_plan(module):
  verify_module_dependencies(module)
  boot_module_with_allowed_dependencies()
  stimulate_public_use_case()
  assert_state_change_or_domain_event()
  fail_if_internal_boundary_is_crossed()
```

Migration test flow:

```text
test_migration(version):
  start_postgres_container()
  apply_migrations_until(version - 1)
  seed_representative_data()
  apply_migration(version)
  assert_schema_contract()
  assert_data_parity()
```

## Bizim Notlarımız / Takım Kararları

- İlk etapta en az iki module için Modulith skeleton hazırlanmalıdır: attendance ve messaging.
- Persistence ve migration testleri için PostgreSQL Testcontainers kullanılmalıdır.
- Migration testleri [Flyway Migrations ve Zero-Downtime DB Changes](../database-architecture/flyway-zero-downtime-migration-strategy) güvenlik beklentilerini desteklemelidir.
- Module testleri [Modular Monolith ve Spring Modulith](../engineering-notes/modular-monolith-and-spring-modulith) boundary yaklaşımıyla uyumlu olmalıdır.

## Harici Referanslar

- Spring Modulith: [Integration Testing Application Modules](https://docs.spring.io/spring-modulith/reference/testing.html)
- Testcontainers for Java: [Postgres Module](https://java.testcontainers.org/modules/databases/postgres/)

## Sözlük

- Application module: modular uygulamada domain boundary.
- `@ApplicationModuleTest`: module-scoped test için Spring Modulith annotation'ı.
- Testcontainers: disposable container dependency sağlayan test library'si.
- Migration test: schema change davranışını doğrulayan test.
- Boundary verification: module'lerin sadece izinli API'lere bağlı olduğunu kontrol eder.

## Araştırma Keywordleri

- `spring modulith testing testcontainers`
- `flyway migration tests integration tests`
- `ApplicationModuleTest Scenario API`
- `PostgreSQLContainer spring boot test`
- `modular monolith boundary verification`

## Sonuç

Module ve migration testleri mimariyi sessiz drift'e karşı korur. Spring Modulith boundary ve module behavior doğrular; Testcontainers ise database testlerini production'a yakın hale getirir.
