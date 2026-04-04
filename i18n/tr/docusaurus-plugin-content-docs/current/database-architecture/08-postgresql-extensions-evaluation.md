---
title: PostgreSQL Extension Değerlendirmesi
description: İhtiyaç odaklı PostgreSQL extension seçimi için operasyonel uyum ve risk değerlendirme yaklaşımı.
sidebar_position: 2
---

## Giriş

PostgreSQL extension'ları geliştirici verimini artırabilir ancak her eklenti uzun vadeli operasyonel sorumluluk getirir.

## Neden Önemli

Gereksiz extension kullanımı:

- upgrade karmaşıklığı,
- güvenlik inceleme yükü,
- managed DB uyumsuzluğu

gibi etkiler üretir.

## Temel Kavramlar

- Extension: PostgreSQL'e ek yetenek kazandıran modül.
- Operational fit: altyapı, backup, restore ve sürüm stratejisine uyum.
- Lifecycle risk: kurulumdan kaldırmaya kadar bakım riski.

## Gerçek Ürün Geliştirmede Problem

"Popüler" diye extension eklemek, ileride migration ve incident süreçlerinde gizli maliyet üretir.

## Yaklaşım / Tasarım

Değerlendirme kontrol listesi:

- Çözdüğü problem net mi?
- Çalıştığımız ortamda destekli mi?
- Güvenlik etkisi nedir?
- Performans etkisi ölçüldü mü?
- Backup/restore etkisi nedir?
- Upgrade/rollback planı var mı?
- Sahibi kim?

Örnek aday sınıfları:

- izleme: `pg_stat_statements`
- zamanlama: `pg_cron`
- kriptografi: `pgcrypto`
- ihtiyaç varsa yaşam döngüsü odaklı `pg_*` araçları

## Sektör Standardı / Best Practice

- Extension setini minimum ve gerekçeli tut.
- Extension registry (sahip, gerekçe, rollback planı) tut.
- Önce staging'de doğrula, sonra production'a taşı.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
evaluate_extension(ext):
  if no_clear_problem(ext):
    deny

  if not environment_compatible(ext):
    deny

  risk = security_risk(ext) + ops_risk(ext) + upgrade_risk(ext)
  value = measurable_value(ext)

  if value <= risk_threshold(risk):
    deny

  approve_with_owner(ext)
  register_decision(ext)
```

## Bizim Notlarımız / Takım Kararları

- PostgreSQL extension'ları gözden geçirilecek, yalnızca ihtiyaç olanlar eklenecek.
- Karar kriteri: gerçek ihtiyaç + operasyonel uyum.
- `pg_*` lifecycle araçları için kullanım senaryosu netleşmeden üretime alınmayacak.
- Bu kararlar [Read / Write Replica Kuralları](./read-write-replica-strategy) ile çelişmeyecek.

## Sözlük

- Managed DB compatibility: bulut sağlayıcı destek uyumu.
- Rollback planı: sorun durumunda geri dönüş stratejisi.
- Ownership: extension bakım sorumluluğu.

## Araştırma Keywordleri

- `postgresql extension production checklist`
- `pg_stat_statements operations`
- `postgres extension governance`

## Sonuç

Extension seçimi teknoloji merakıyla değil, problem odaklı ve operasyon gerçekliğine uyumlu yapılmalıdır.
