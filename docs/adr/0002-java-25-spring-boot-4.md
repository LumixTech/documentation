---
title: "ADR-002: Java 25 LTS + Spring Boot 4"
description: Backend dili Java 25 LTS, framework Spring Boot 4.x; Java 25 resmi desteği Spring Boot 4.0+ ile gelir. Java 21 LTS ve non-LTS sürümler değerlendirildi.
sidebar_position: 2
---

# ADR-002: Java 25 LTS + Spring Boot 4

| Alan | Değer |
|---|---|
| **Status** | Accepted |
| **Tarih** | 2026-06-06 |
| **Deciders** | Lumix çekirdek ekip |
| **Sprint** | Sprint 0 |

> **Güncelleme (2026-07-05):** Bu ADR ilk halinde framework olarak **"Spring Boot 3.6"** diyordu. İki
> gerçek bunu geçersiz kıldı: (1) **Spring Boot 3.6 hiç yayınlanmadı** — 3.5.x'ten sonra doğrudan 4.0.x
> geldi; (2) **Java 25 resmî desteği Spring Boot 4.0 ile başlıyor** (3.5.x resmî olarak Java 17–24). Yani
> "Java 25 + Spring Boot 3.x" kombinasyonu aynı anda mümkün değil. **Java 25 kararı korundu; framework
> Spring Boot 4.1'e taşındı.** Ayrıntı ve bedel için Consequences bölümüne bakın. (Tetikleyen: CI'ın
> Gradle 8.14 + JDK 25 uyumsuzluğu; bkz. [ADR-006](./0006-gradle-kotlin-dsl-build-tool.md) ve
> `campus/backend` build zinciri.)

## Context

Backend dili ve framework'ü seçilecek. Ekibin ana bilgisi **Java + Spring**. Proje **2026 Haziran'da**
başlıyor, yani uzun ömürlü olması beklenen kod bugün yazılıyor. Kritik güçler:

- **Uzun destek (LTS):** Kurumsal, on-prem, müşteri başına kurulan bir üründe sık major-Java migration'ı
  istemiyoruz; uzun süre güvenlik yaması alabilmeliyiz.
- **Modern Java kabiliyetleri:** Özellikle **virtual threads** (yüksek eşzamanlılık, imperative kod ile),
  ayrıca pattern matching, records, sequenced collections, **structured concurrency (Java 25'te stable)**.
- **Framework uyumu:** Seçilen JDK'yı ilk-sınıf destekleyen kararlı bir Spring Boot sürümü olmalı. Bu kritik
  güç, aşağıdaki framework kararını doğrudan belirledi: Java 25'i resmî destekleyen ilk Spring Boot hattı
  **4.0**'dır.

## Decision

Backend'de **Java 25 LTS** + **Spring Boot 4.x** (şu an **4.1**) kullanıyoruz. Proje 2026-06'da başladığı için
doğrudan **en güncel LTS** ile başlıyoruz (Java 21 → 25 ara migration'ı hiç yaşanmadan). Non-LTS sürümler
(sadece ~6 ay destek) kurumsal on-prem ürün için elendi. Toolchain sürümü `.sdkmanrc` / `.tool-versions` /
CI'da tek kaynaktan sabitlenir (`temurin-25`).

Framework sürümü **Java 25 kararının bir sonucudur**: Java 25 bytecode ve runtime'ını resmî destekleyen ilk
Spring Boot hattı 4.0'dır (Spring Framework 7.x tabanlı). Spring Boot 3.5.x resmî olarak Java 17–24 destekler;
Java 25'i çalıştırsa da sertifikalı değildir. Dolayısıyla "Java 25 önceliği" doğrudan **Spring Boot 4.x**
demektir.

## LTS karşılaştırma matrisi

| Ölçüt | Java 21 LTS | **Java 25 LTS (seçildi)** | Java 23/24/26 (non-LTS) |
|---|---|---|---|
| Çıkış | Eyl 2023 | **Eyl 2025** | ara sürümler |
| Destek süresi | ~2031'e kadar | **~2030+'a kadar** | **~6 ay** (bir sonraki sürüme kadar) |
| Virtual threads | Stable | **Stable** | Stable |
| Structured concurrency | Preview | **Stable (JEP 505)** | değişken |
| Scoped values | Preview | **Stable (JEP 506)** | değişken |
| 2026-06'da "en güncel LTS" mi? | Hayır (bir önceki) | **Evet** | — |
| Resmî destekleyen Spring Boot | 3.x ve 4.x | **4.0+** | değişken |
| Kurumsal on-prem uygunluk | İyi | **En iyi** | **Uygun değil** (kısa destek) |

**Neden 21 değil:** 21 geçerli bir alternatifti, ama 2026-06'da başlayan bir projede 21 ile başlamak
yakın gelecekte 21→25 migration sprint'i anlamına gelirdi. Doğrudan 25 ile başlamak bu ara adımı ortadan
kaldırır ve daha uzun modern-Java penceresi verir.

**Neden non-LTS değil:** Java 23/24/26 gibi feature-release'ler yalnızca bir sonraki sürüme kadar (~6 ay)
destek alır. Müşteri başına kurulan, uzun ömürlü bir üründe 6 ayda bir zorunlu JDK yükseltmesi kabul
edilemez operasyonel risktir.

## Consequences

- **Olumlu:** ~2030'a kadar güvenlik desteği; virtual threads ile reactive'e geçmeden yüksek throughput;
  modern dil özellikleriyle daha az boilerplate; Spring Boot 4.x'in Java 25 first-class desteği ve Spring
  Framework 7.x tabanı.
- **Olumsuz / bedel:** İki katman "yenilik" bedeli üstleniliyor: (a) Java 25 yeni olduğu için bazı
  araç/kütüphaneler (statik analiz, formatter, bytecode agent'ları) 25 söz dizimini/class-file sürümünü geç
  destekleyebilir — nitekim CI'da ArchUnit'in ASM'i class-file v69'u okuyamadı, Gradle 8.14 JDK 25'te
  çalışmadı; (b) Spring Boot 4.0 taze bir **major** sürüm — on-prem stabilite açısından ADR'nin ilk ruhu
  "major'dan kaçınmak"tı, ama Java 25 önceliği bunu zorunlu kıldı. Boot 3.x → 4.x geçişi Jakarta EE 11,
  Spring Framework 7 ve yeniden paketlenen test/starter modülleri gibi kırıcı değişiklikler getirir.
- **Azaltıcı önlemler:** Toolchain sürümü tek kaynaktan pinlenir; kütüphaneler Spring Boot BOM ile hizalanır;
  formatter/analyzer/Gradle sürümleri Java 25'i destekleyen sürümlere sabitlenir (Gradle ≥ 9.1, ArchUnit
  ≥ 1.4, Spotless ≥ 7). Major geçişin runtime etkisi `service-template` üzerinde bir kez doğrulanır
  (integration smoke + image build) ve türeyen servislere hazır yapı olarak dağıtılır.

## Alternatives Considered

- **Java 25 + Spring Boot 3.5.x** — ADR'nin ilk "3.x LTS, major'dan kaçın" ruhuna en yakını. → **Elendi:**
  Spring Boot 3.5.x Java 25'i resmî desteklemez (17–24). Java 25'i sertifikasız çalıştırmak on-prem üründe
  kabul edilemez risktir; Java 25 önceliği korunduğu için framework 4.x'e taşındı.
- **Java 24 + Spring Boot 3.5.x** — Java'yı bir tık geri alıp Boot'u 3.x'te tutmak. → **Elendi:** Java 25'in
  stable structured concurrency/scoped values kazanımlarından ve "en güncel LTS ile başla" ilkesinden
  vazgeçmek anlamına gelirdi.
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
