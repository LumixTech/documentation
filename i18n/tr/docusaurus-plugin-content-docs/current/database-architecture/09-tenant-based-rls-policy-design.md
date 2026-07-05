---
title: Tenant Tabanlı Row-Level Security (RLS)
description: tenant_id temelli RLS policy tasarımıyla veritabanı seviyesinde tenant izolasyonu.
sidebar_position: 3
---

## Giriş

Multi-tenant sistemlerde sadece uygulama katmanı filtreleri yeterli güvenlik sınırı değildir. `RLS policy`, veritabanı katmanında tenant izolasyonunu zorunlu kılar.

## Neden Önemli

Kodda bir `WHERE tenant_id = ...` unutulması, çapraz tenant veri sızıntısına yol açabilir. RLS ikinci savunma hattı sağlar.

## Temel Kavramlar

- `tenant_id`: tenant sınır anahtarı.
- RLS: satır düzeyi erişim politikası.
- `USING`: okuma görünürlük kuralı.
- `WITH CHECK`: yazma doğrulama kuralı.
- Session context: bağlantı bazlı tenant bilgisi.

## Gerçek Ürün Geliştirmede Problem

Servis sayısı arttıkça sadece kod disipliniyle tenant sınırı korumak zorlaşır.

## Yaklaşım / Tasarım

Politika stratejisi:

- Tenant-scope tablolarda `tenant_id` zorunlu.
- RLS explicit olarak açılır.
- Read için `USING`, write için `WITH CHECK` yazılır.
- Her istekte DB session'a tenant context set edilir.

Örnek:

```sql
ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;

CREATE POLICY invoices_tenant_read
  ON invoices
  FOR SELECT
  USING (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE POLICY invoices_tenant_write
  ON invoices
  FOR INSERT, UPDATE
  WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);
```

## Sektör Standardı / Best Practice

- Policy isimlendirmesini standartlaştır.
- Tenant context yoksa fail closed davran.
- Bypass rollerini minimumda tut ve izlemeye al.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
on REQUEST_START(auth):
  set_db_setting('app.tenant_id', auth.tenant_id)

on QUERY(table):
  if table_has_rls(table):
    db_enforces_policy(table)
```

## Bizim Notlarımız / Takım Kararları

- RLS policy'ler `tenant_id` bazlı yazılacak.
- Tenant izolasyonu hem uygulama hem DB katmanında garanti altına alınacak.
- Yetki tarafında [Hibrit Yetkilendirme Modeli - RBAC + ABAC](../security-compliance/hybrid-rbac-abac-authorization) ile birlikte çalışacak.

## Sözlük

- Fail closed: gerekli bağlam yoksa erişimi engelleme yaklaşımı.
- WITH CHECK: insert/update sırasında policy doğrulaması.
- Tenant isolation: tenantlar arası veri sınırı garantisi.

## Araştırma Keywordleri

- `postgresql tenant rls policy`
- `using with check rls`
- `current_setting tenant_id`

## Sonuç

Tenant güvenliği yalnızca uygulama koduna bırakılamaz. `RLS policy` ile veritabanı seviyesinde ikinci güvenlik sınırı kurulmalıdır.
