---
title: "ADR-002: Java 25 LTS + Spring Boot 3.6"
description: Backend dili Java 25 LTS, framework Spring Boot 3.6; Java 21 LTS ve non-LTS sürümler değerlendirildi.
sidebar_position: 2
---

# ADR-002: Java 25 LTS + Spring Boot 3.6

| Alan | Değer |
|---|---|
| **Status** | Accepted |
| **Tarih** | 2026-06-06 |
| **Deciders** | Lumix çekirdek ekip |
| **Sprint** | Sprint 0 |

## Context

Backend dili ve framework'ü seçilecek. Ekibin ana bilgisi **Java + Spring**. Proje **2026 Haziran'da**
başlıyor, yani uzun ömürlü olması beklenen kod bugün yazılıyor. Kritik güçler:

- **Uzun destek (LTS):** Kurumsal, on-prem, müşteri başına kurulan bir üründe sık major-Java migration'ı
  istemiyoruz; uzun süre güvenlik yaması alabilmeliyiz.
- **Modern Java kabiliyetleri:** Özellikle **virtual threads** (yüksek eşzamanlılık, imperative kod ile),
  ayrıca pattern matching, records, sequenced collections, **structured concurrency (Java 25'te stable)**.
- **Framework uyumu:** Seçilen JDK'yı ilk-sınıf destekleyen kararlı bir Spring Boot sürümü olmalı.

## Decision

Backend'de **Java 25 LTS** + **Spring Boot 3.6** kullanıyoruz. Proje 2026-06'da başladığı için doğrudan
**en güncel LTS** ile başlıyoruz (Java 21 → 25 ara migration'ı hiç yaşanmadan). Non-LTS sürümler
(sadece ~6 ay destek) kurumsal on-prem ürün için elendi. Toolchain sürümü `.sdkmanrc` / `.tool-versions` /
CI'da tek kaynaktan sabitlenir (`temurin-25`).

## LTS karşılaştırma matrisi

| Ölçüt | Java 21 LTS | **Java 25 LTS (seçildi)** | Java 23/24/26 (non-LTS) |
|---|---|---|---|
| Çıkış | Eyl 2023 | **Eyl 2025** | ara sürümler |
| Destek süresi | ~2031'e kadar | **~2030+'a kadar** | **~6 ay** (bir sonraki sürüme kadar) |
| Virtual threads | Stable | **Stable** | Stable |
| Structured concurrency | Preview | **Stable (JEP 505)** | değişken |
| Scoped values | Preview | **Stable (JEP 506)** | değişken |
| 2026-06'da "en güncel LTS" mi? | Hayır (bir önceki) | **Evet** | — |
| Kurumsal on-prem uygunluk | İyi | **En iyi** | **Uygun değil** (kısa destek) |

**Neden 21 değil:** 21 geçerli bir alternatifti, ama 2026-06'da başlayan bir projede 21 ile başlamak
yakın gelecekte 21→25 migration sprint'i anlamına gelirdi. Doğrudan 25 ile başlamak bu ara adımı ortadan
kaldırır ve daha uzun modern-Java penceresi verir.

**Neden non-LTS değil:** Java 23/24/26 gibi feature-release'ler yalnızca bir sonraki sürüme kadar (~6 ay)
destek alır. Müşteri başına kurulan, uzun ömürlü bir üründe 6 ayda bir zorunlu JDK yükseltmesi kabul
edilemez operasyonel risktir.

## Consequences

- **Olumlu:** ~2030'a kadar güvenlik desteği; virtual threads ile reactive'e geçmeden yüksek throughput;
  modern dil özellikleriyle daha az boilerplate; Spring Boot 3.6'nın Java 25 first-class desteği.
- **Olumsuz / bedel:** Java 25 yeni olduğu için bazı araç/kütüphaneler (statik analiz, formatter, agent'lar)
  25 söz dizimini geç destekleyebilir; ekosistem olgunluğu 21'e göre bir tık geride.
- **Azaltıcı önlemler:** Toolchain sürümü tek kaynaktan pinlenir; kütüphaneler Spring Boot BOM ile hizalanır;
  formatter/analyzer sürümleri gerektiğinde bump edilir. Spring Boot 3.6, 2025 sonuna kadar LTS destekli.

## Alternatives Considered

- **Java 21 LTS** — Olgun, yaygın, uzun destekli. → **Elendi (yakın kayıp):** 2026-06'da artık *bir önceki*
  LTS; 25 ile başlamak gereksiz bir ara migration'ı önler. Yine de geçerli bir alternatifti.
- **Java 23 / 24 / 26 (non-LTS)** — En yeni dil özellikleri. → **Elendi:** ~6 ay destek; on-prem/kurumsal
  ürün için sürdürülemez.
- **Kotlin (JVM)** — Modern, null-safety. → **Elendi:** Ekip ana bilgisi Java; Spring + Java ekosistem
  olgunluğu ve öğrenme maliyeti. (Not: build script'lerde Kotlin DSL kullanılıyor — bu dil kararı değildir,
  bkz. [ADR-006](./0006-gradle-kotlin-dsl-build-tool.md).)
- **Quarkus / Micronaut (framework)** — Hızlı startup, cloud-native. → **Elendi:** Spring ekosistemi ekipte
  daha yaygın ve olgun; ayrıntı: [Spring Boot Foundation](../03-backend/01-spring-boot-foundation.md).

## References

- [Java 25 & Virtual Threads](../03-backend/02-java-25-virtual-threads.md)
- [Spring Boot Foundation](../03-backend/01-spring-boot-foundation.md)
- [Teknoloji Kararları — Tek Sayfa Özet](../00-overview/02-technology-stack-decisions.md)
