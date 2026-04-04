---
title: Read / Write Replica Kuralları
description: read replica ve write primary arasında kural tabanlı yönlendirme ve tutarlılık yönetimi.
sidebar_position: 4
---

## Giriş

`read replica` kullanımı sadece ölçekleme değil, tutarlılık kararıdır. Bu nedenle yönlendirme kuralları net olmalıdır.

## Neden Önemli

Kural yoksa:

- kullanıcı yeni yazdığı veriyi göremeyebilir,
- kritik akışlarda yanlış kararlar oluşabilir,
- hata ayıklama zorlaşır.

## Temel Kavramlar

- `write primary`: yazma ve güçlü tutarlılık için ana düğüm.
- `read replica`: ölçeklenebilir okuma düğümü.
- Replication lag: primary-replica gecikmesi.
- Read-after-write: yazma sonrası taze okuma beklentisi.

## Gerçek Ürün Geliştirmede Problem

"Tüm SELECT replica'ya gitsin" yaklaşımı auth, payment, permission gibi kritik akışlarda kırılma yaratır.

## Yaklaşım / Tasarım

Yönlendirme kural seti:

- Her zaman `write primary`
  - tüm write işlemleri
  - write sonrası anlık doğrulama okumaları
  - auth/session/permission gibi güvenlik kritik okumalar

- `read replica` uygun
  - analytics ekranları
  - kısa gecikmeyi tolere eden listeleme sorguları

- Koşullu yönlendirme
  - lag eşiği aşılırsa primary fallback
  - feature bazlı consistency gereksinimi

## Sektör Standardı / Best Practice

- Consistency gereksinimini ürün kuralı olarak yaz.
- Routing kararını merkezi modülde yönet.
- Lag metriklerini görünür kıl ve alarm üret.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
route_query(query, context):
  if query.is_write:
    return write_primary

  if context.requires_read_after_write:
    return write_primary

  if context.domain in ['auth', 'permission', 'payment']:
    return write_primary

  if replica_lag_too_high():
    return write_primary

  return read_replica
```

## Bizim Notlarımız / Takım Kararları

- `read replica` / `write primary` kullanımı kural tabanlı olacak.
- Güvenlik kritik okumalar primary'de kalacak.
- Replica kullanımı ürün bağlamındaki tazelik ihtiyacına göre belirlenecek.

## Sözlük

- Eventual consistency: verinin replica'ya gecikmeli yansıması.
- Freshness: okunan verinin güncellik seviyesi.
- Query routing: sorgunun uygun node'a yönlendirilmesi.

## Araştırma Keywordleri

- `read write replica routing rules`
- `read after write consistency`
- `replication lag fallback strategy`

## Sonuç

Replica mimarisi ancak net kural setiyle güvenli çalışır. Performans ve doğruluk dengesi, domain bazlı yönlendirme kararlarıyla kurulmalıdır.
