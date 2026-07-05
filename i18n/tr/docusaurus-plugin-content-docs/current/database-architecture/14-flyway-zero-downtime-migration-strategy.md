---
title: Flyway Migration ve Zero-Downtime Veritabanı Değişiklikleri
description: Flyway, backward-compatible schema tasarımı ve expand/contract rollout modeli ile production-safe veritabanı değişiklik stratejisi.
sidebar_position: 5
---

## Giriş

Veritabanı migration'ları production ortamındaki en yüksek riskli değişikliklerdendir. SQL doğru olsa bile rollout-safe tasarlanmadıysa canlı trafiği bozabilir.

Bu doküman, Flyway merkezli ve zero-downtime odaklı backward-compatible şema evrim yaklaşımını tanımlar.

## Neden Önemli

Uyumluluk planı olmadan şema değişikliği yapılırsa:

- eski uygulama instance'ları yeni şemada hata verir,
- uzun lock'lar kritik write akışlarını bloklar,
- rollback veri kaybı riskiyle zorlaşır,
- production incident tüm tenant'ları etkileyebilir.

Zero-downtime migration yaklaşımı, şema değişikliği ile uygulama cutover'ını ayrıştırarak riski düşürür.

## Temel Kavramlar

- Flyway migration: sıralı ve versiyonlu şema değişiklikleri (`V...__description.sql`).
- Backward-compatible schema change: rollout sırasında hem eski hem yeni uygulama versiyonuyla çalışabilen değişiklik.
- Expand and contract: önce uyumlu yapıları ekleyip, eski yapıları daha sonra kaldıran aşamalı model.
- Dual-read/dual-write: geçiş döneminde eski ve yeni şema yollarını birlikte çalıştırma.
- Backfill: mevcut veriyi kontrollü batch'lerle yeni şemaya taşıma.
- Contract phase: trafik tamamen yeni yola geçmeden eski kolon/tabloyu kaldırmama kuralı.

## Gerçek Ürün Geliştirmede Problem

Gerçek deploy'lar atomik değildir. Rolling deploy sırasında bazı pod'lar eski, bazıları yeni kod çalıştırır. Migration tek versiyon varsayımıyla yazılırsa kolon rename veya tablo split gibi işlemler release ortasında read/write kırar.

## Yaklaşım / Tasarım

### Flyway ile Migration Yönetişimi

- Geri alınamaz her şema adımı için ayrı migration birimi kullan.
- Mümkün olan adımları idempotent yaz (`IF NOT EXISTS`, guard'lı update).
- Production öncesi lock etkisini değerlendir (`ALTER TABLE` tipi, index build stratejisi, table rewrite riski).
- Paralel release yapan ekiplerde timestamp bazlı versiyonlama tercih et.

Örnek adlandırma:

- `V2026041801__expand_add_student_display_name.sql`
- `V2026041802__expand_create_student_profile_table.sql`
- `V2026041803__contract_drop_legacy_student_columns.sql`

### Expand and Contract Oyun Planı

1. Expand
- Yeni nullable kolon/tablo/index ekle.
- Eski şema yolunu çalışır bırak.
- Uygulamayı dual-read/dual-write ile deploy et.

2. Migrate
- Tarihsel veriyi batch backfill ile taşı.
- Uyuşmazlık (parity) metriklerini izle.

3. Switch
- Read akışını yeni şemaya al.
- Geri dönüş fallback'ini kontrollü bir süre tut.

4. Contract
- Doğrulama bitmeden eski kolon/tabloyu düşme.

### 2 Aşamalı Senaryo 1: Kolon Rename (Production-Safe)

Hedef: `students.full_name` kolonunu `students.display_name` olarak yeniden adlandırmak.

Aşama 1 (`expand + compatibility`)

```sql
-- V2026041801__expand_add_display_name.sql
ALTER TABLE students ADD COLUMN IF NOT EXISTS display_name text;

-- Backfill: uygulama job'ı veya kontrollü SQL penceresi
UPDATE students
SET display_name = full_name
WHERE display_name IS NULL
  AND full_name IS NOT NULL;
```

Aşama 1 uygulama davranışı:

- hem `full_name` hem `display_name` yaz,
- okuma için `COALESCE(display_name, full_name)` kullan.

Aşama 2 (`contract`)

```sql
-- V2026041804__contract_drop_full_name.sql
ALTER TABLE students DROP COLUMN IF EXISTS full_name;
```

Aşama 2 ön koşulu:

- tüm uygulama instance'ları sadece `display_name` okuyup yazıyor olmalı,
- uyuşmazlık metriği üzerinde mutabık kalınan izleme penceresinde sıfır olmalı.

### 2 Aşamalı Senaryo 2: Tablo Split (Production-Safe)

Hedef: `students` tablosundaki profile alanlarını `student_profiles` tablosuna ayırmak.

Aşama 1 (`expand + dual-write + backfill`)

```sql
-- V2026041802__expand_create_student_profiles.sql
CREATE TABLE IF NOT EXISTS student_profiles (
  student_id uuid PRIMARY KEY REFERENCES students(id),
  bio text,
  avatar_url text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
```

Aşama 1 uygulama davranışı:

- read'te eski kolonlardan oku, yeni tabloda fallback desteği ver,
- eski kolon + `student_profiles` için dual-write yap,
- mevcut veriyi batch backfill ile taşı.

Aşama 2 (`contract`)

```sql
-- V2026041805__contract_drop_profile_columns_from_students.sql
ALTER TABLE students
  DROP COLUMN IF EXISTS bio,
  DROP COLUMN IF EXISTS avatar_url;
```

Aşama 2 ön koşulu:

- tüm read trafiği `student_profiles` üzerinden servis ediliyor olmalı,
- backfill tamlığı ve veri parity kontrolü geçmiş olmalı.

## Sektör Standardı / Best Practice

- Zero-downtime, sadece SQL doğruluğu değil release mimarisi meselesidir.
- Expand ve contract adımlarını aynı production release içine koyma.
- Önce additive değişiklikleri tercih et (`ADD COLUMN`, `ADD TABLE`).
- Büyük tablolarda backfill'i batch ve throttled yürüt.
- PostgreSQL'de mümkün olduğunda düşük-lock tekniklerini kullan (`CREATE INDEX CONCURRENTLY`, gecikmeli constraint doğrulama).

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
deploy_schema_change(change_request):
  if not backward_compatible(change_request.expand_sql):
    reject

  apply_flyway_expand_migrations()
  deploy_app_dual_read_write()

  run_backfill_in_batches()
  if data_parity_not_ok():
    stop_and_investigate

  switch_reads_to_new_schema()
  observe_metrics(window='agreed_period')

  if stable_and_verified():
    apply_flyway_contract_migrations()
  else:
    keep_legacy_path_until_stable
```

## Bizim Notlarımız / Takım Kararları

- Paylaşılan ortamlarda şema versiyon kontrolü için Flyway migration zorunlu.
- Zero-downtime DB değişiklikleri backward-compatible expand/contract modeliyle yapılmalı.
- Kolon rename ve tablo split tek atımlık SQL değil, çok release'li akış olarak planlanmalı.
- Contract adımı, parity kontrolü ve gözlem metrikleri geçmeden çalıştırılmamalı.

## Sözlük

- Flyway: sıralı migration dosyalarını uygulayan şema versiyonlama aracı.
- Zero-downtime migration: deploy sırasında servis kesintisi oluşturmayan migration yaklaşımı.
- Backward-compatible schema change: eski ve yeni uygulama versiyonlarıyla birlikte çalışabilen şema değişikliği.
- Expand and contract: önce ekle, sonra kaldır prensibine dayalı aşamalı migration deseni.
- Backfill: mevcut veriyi yeni şema formuna taşıma süreci.
- Dual-write: geçiş döneminde eski ve yeni şema yoluna birlikte yazma.

## Araştırma Keywordleri

- `flyway zero downtime migration strategy`
- `expand contract database migration pattern`
- `backward compatible schema changes`
- `postgresql column rename without downtime`
- `table split migration dual write backfill`

## Sonuç

Production-safe şema evrimi, süreç disiplini ve tasarım disiplini birlikte gerektirir. Flyway versiyon kontrolünü, expand/contract ise rollout güvenliğini sağlar. Birlikte kullanıldığında yüksek etkili DB değişiklikleri öngörülebilir ve yönetilebilir hale gelir.
