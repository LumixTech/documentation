---
title: "ADR-008: Reference data — typed lookup tablolar (enum değil)"
description: Enumere domain değerleri (okul seviyesi, atama rolü) hard-coded enum yerine kavram-başına typed reference tabloda; config-driven seed + FK. Generic tek "parameters" (OTLT) tablosu ve enum elendi.
sidebar_position: 8
---

# ADR-008: Reference data — typed lookup tablolar (enum değil)

| Alan | Değer |
|---|---|
| **Status** | Accepted |
| **Tarih** | 2026-07-11 |
| **Deciders** | Lumix çekirdek ekip |
| **Sprint** | Sprint 1 |

## Context

Domain'de **sabit / enumere değer kümeleri** var: okul seviyesi (ilkokul / ortaokul / lise), atama rolü
(öğretmen / öğrenci) ve ileride not skalası, ödeme durumu tipleri, bildirim kanalları gibi onlarca liste.
Lumix **on-prem, çok-kurulumlu (multi-installation), çok-kiracılı (multi-tenant)** bir platform.

İlk implementasyon bu değerleri **Java `enum` + korumasız `VARCHAR` kolon** olarak taşıyordu — iki dünyanın
da kötüsü: enum'un katılığı (yeni değer = deploy) **ve** DB bütünlüğü yokluğu (kolonda `CHECK` bile yok).
Güçler:

- Farklı kurulumlar/kiracılar **farklı değer setleri** isteyebilir (anaokulu, meslek lisesi, imam hatip;
  asistan, rehber öğretmen, gözlemci).
- İş admini **deploy beklemeden** değer ekleyebilmeli.
- Değerin **metadata**'sı olabilir (görünen ad, i18n etiketi, sıralama, aktif/pasif).
- Değeri **referans veren** satırların bütünlüğü DB'de garanti edilmeli (yanlış kategoriye bağlanma olmasın).

## Decision

Enumere domain değerleri **kavram-başına ayrı, typed reference (lookup) tablosunda** tutulur — `school_levels`,
`assignment_roles`, … Tüketen tablolar bu tablolara **FK** ile bağlanır (`schools.level_id → school_levels`,
`class_assignments.role_id → assignment_roles`). Değerler **config-driven seed** ile gelir (Flyway `R__`
repeatable migration, idempotent `ON CONFLICT`; bkz. [ADR-010](./0010-flyway-migration-framework.md)).

- Reference tablo standart örüntüsü: `id (UUID)`, `code (UNIQUE)`, `name`, `active` [+ `sort_order`,
  kavrama-özel alanlar].
- **Installation-global**: reference veri tenant taşımaz, RLS uygulanmaz (kurulum başına bir kez seed).
  Tenant-bazlı özelleştirme gerçek ihtiyaç doğunca eklenir (YAGNI).
- Kod bir değere göre **dallanıyorsa** stabil `code` sabiti kullanılır; Java `enum` **zorunlu değil**.

## Consequences

- **Olumlu:** Config-driven (deploy'suz değer ekleme); **güçlü FK bütünlüğü** (bir okul yanlışlıkla bir role
  referans veremez — DB reddeder); kavrama-özel metadata/constraint/index; self-documenting şema; raporlamada
  join'lenebilir değer bilgisi.
- **Olumsuz / bedel:** Kavram-başına ayrı tablo → çok sayıda küçük tablo; yeni lookup türü = yeni tablo +
  migration.
- **Azaltıcı önlemler:** Tüm reference tablolar tek örüntü; seed `R__reference_data` ile merkezî ve idempotent;
  gerçekten çok sayıda serbest-form ayar (knob) olursa onlar için **ayrı** system-settings (key-value) tablosu
  kullanılır — o generic tablo *ayarlar* içindir, domain'in FK verdiği *reference data* için değil.

## Alternatives Considered

- **Hard-coded Java `enum` (`@Enumerated`)** — Derleme güvenli, sıfır tablo. → **Elendi:** Değer eklemek
  deploy + migration gerektirir; kurulum/kiracı özelleştirmesi imkânsız; DB'de yalnızca korumasız text kaldığı
  için hem katı hem bütünlüksüz (ilk implementasyonun sorunu buydu).
- **Generic tek "parameters" + "parameter_groups" tablosu (OTLT / MUCK, tablo adına göre)** — Tek yönetim
  ekranı, yeni tür = yeni satır. → **Elendi:** FK bütünlüğü çöker — `schools.level_id → parameters(id)` bir
  **rol** değerine işaret edebilir ve DB bunu engelleyemez; tüm değerler tek datatype; zayıf constraint/index;
  tek hotspot tablo; global+tenant karışınca RLS karmaşası. Typed tabloda aynı hata **canlı doğrulandı** ve DB
  tarafından reddedildi (`violates foreign key constraint "schools_level_id_fkey"`).
- **DB `CHECK` constraint (`CHECK (level IN ('ILKOKUL', ...))`)** — Tablosuz DB-seviyesi kısıt. → **Elendi:**
  Değer eklemek yine migration (deploy) gerektirir; config-driven değil; metadata (görünen ad, sıra, aktif)
  taşınamaz.

## References

- [Installation / Tenant / Scope Modeli](../01-tenancy-and-domain-model/01-installation-tenant-scope.md) — reference data notu
- [Domain Servisleri](../01-tenancy-and-domain-model/02-domain-services-overview.md) — organization sahip olduğu veri
- [ADR-010: Flyway migration framework](./0010-flyway-migration-framework.md) — `R__` seed mekanizması
- `campus/backend/organization-service/` — `school_levels` / `assignment_roles` + `V002/V003` + `R__reference_data`
