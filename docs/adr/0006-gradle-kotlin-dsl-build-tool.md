---
title: "ADR-006: Gradle (Kotlin DSL) build tool"
description: Build aracı Gradle + Kotlin DSL; Maven multi-module değerlendirildi ve elendi.
sidebar_position: 6
---

# ADR-006: Gradle (Kotlin DSL) build tool

| Alan | Değer |
|---|---|
| **Status** | Accepted |
| **Tarih** | 2026-06-06 |
| **Deciders** | Lumix çekirdek ekip |
| **Sprint** | Sprint 0 |

## Context

Mono-repo'da ([ADR-001](./0001-mono-repo.md)) 12 servis × ~7 hexagonal modül
([ADR-005](./0005-hexagonal-architecture.md)) build edilecek. Ayrıca gRPC/Protobuf **code generation**
([ADR-003](./0003-grpc-protobuf-inter-service.md)) build'e entegre olmalı. Build aracı seçimi bir süre
netleşmemişti (Maven/Gradle tutarsızlığı yaşandı); bu ADR kararı **yazılı** hale getirir.

Ölçütler: **çok-modüllü ergonomi**, **codegen entegrasyonu** (protobuf), **merkezî sürüm yönetimi**,
**CI cache/incremental build hızı**, **tip-güvenli ve IDE-destekli** yapı script'leri.

## Decision

Build aracı **Gradle**, script dili **Kotlin DSL** (`*.gradle.kts`). Sürümler tek bir **version catalog**'da
(`gradle/libs.versions.toml`) toplanır; ortak convention'lar kök `build.gradle.kts`'te. gRPC codegen
`protobuf-gradle-plugin` ile bağlanır. Toolchain (JDK 25) `gradle/wrapper` + `.sdkmanrc`/CI ile pinlenir.

> **Not:** Kotlin DSL bir **build-script dili** kararıdır — üretim kodu **saf Java**'dır
> ([ADR-002](./0002-java-25-spring-boot-4.md)). Kotlin DSL ≠ projede Kotlin kullanımı.

## Gradle vs Maven karşılaştırması

| Ölçüt | **Gradle + Kotlin DSL (seçildi)** | Maven (multi-module) |
|---|---|---|
| Çok-modül ergonomisi | Esnek; `subprojects{}` / convention plugin ile tekrar az | Parent POM + modül POM'ları; XML tekrarı fazla |
| Protobuf/gRPC codegen | `protobuf-gradle-plugin` ile birinci sınıf, incremental | `protobuf-maven-plugin` var ama incremental/entegrasyon zayıf |
| Merkezî sürüm yönetimi | **Version catalog** (`libs.versions.toml`) + typed accessor | `<dependencyManagement>` / BOM; catalog ergonomisi yok |
| CI cache & hız | **Incremental build + build cache + configuration cache** | Reactor tam build eğilimli; incremental sınırlı |
| Script dili | **Kotlin DSL**: tip-güvenli, IDE autocomplete | XML: verbose, refactor/otomatik-tamamlama zayıf |
| Öğrenme eğrisi | Daha dik (Gradle model) | Daha tanıdık, düz |
| Ekosistem | Spring Boot/gRPC/Testcontainers pluginleri olgun | Olgun ama plugin esnekliği düşük |

## Consequences

- **Olumlu:** Codegen ve çok-modül yapı zahmetsiz; version catalog ile sürüm drift'i yok; build cache +
  incremental ile CI hızlı; Kotlin DSL'de tip-güvenli, IDE-destekli script; `./gradlew` wrapper ile
  makineler arası tutarlı sürüm.
- **Olumsuz / bedel:** Gradle'ın öğrenme eğrisi Maven'dan dik; Kotlin DSL örnekleri internette Groovy'den az;
  bazı pluginler configuration-cache ile henüz uyumsuz (örn. `protobuf-gradle-plugin` → CC kapalı).
- **Azaltıcı önlemler:** Convention'lar kök build'de merkezîleştirilir; version catalog tek kaynak; CC
  gerektiğinde ilgili plugin izole/bump edilir; `service-template` ile hazır Gradle yapısı türetilir.

## Alternatives Considered

- **Maven (multi-module)** — Java dünyasında en tanıdık, düz, geniş dokümantasyon. → **Elendi:** XML tekrarı
  çok-modülde ağır; protobuf/gRPC codegen entegrasyonu ve incremental build Gradle kadar güçlü değil; merkezî
  sürüm için version-catalog benzeri ergonomi yok; CI incremental/cache zayıf.
- **Gradle + Groovy DSL** — Daha çok örnek, dinamik. → **Elendi:** Tip-güvenliği ve IDE autocomplete Kotlin
  DSL'de daha iyi; refactor daha güvenli.
- **Bazel** — Devasa mono-repolarda güçlü incremental. → **Elendi:** 2 kişilik ekip için aşırı karmaşık;
  Spring/JVM ergonomisi ve topluluk desteği Gradle'a göre zayıf.

## References

- [Spring Boot Foundation](../03-backend/01-spring-boot-foundation.md)
- [gRPC Service Communication](../03-backend/03-grpc-service-communication.md) — protobuf-gradle-plugin codegen
- `campus/backend/` — Gradle Kotlin DSL + version catalog referans yapısı
