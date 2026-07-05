---
title: "ADR-004: Microservice topolojisi (12 servis, no shared lib)"
description: 12 bağımsız microservice, DB-per-service; servisler arası paylaşılan kütüphane yok (duplicate kod kabul).
sidebar_position: 4
---

# ADR-004: Microservice topolojisi (12 servis, no shared lib)

| Alan | Değer |
|---|---|
| **Status** | Accepted |
| **Tarih** | 2026-06-06 |
| **Deciders** | Lumix çekirdek ekip |
| **Sprint** | Sprint 0 |

## Context

Lumix müşteri (okul/kurum) başına ayrı kurulan bir platform. İş alanı geniş: kimlik, organizasyon,
akademik, sınav, rehberlik (özel kategori veri), performans, iletişim, finans, dosya, denetim, uyum,
bildirim. Sınırları **DDD bounded context**'lerine göre çizmek istiyoruz. İki karar iç içe:

1. Kaç servis ve hangileri?
2. Servisler ortak kod (auth util, DTO, base entity) paylaşacak mı — yoksa her servis kendi kopyasını mı
   taşıyacak?

Güç: microservice'in asıl değeri **bağımsız deploy + bağımsız evrim**. Paylaşılan bir kütüphane, tüm
servisleri o kütüphanenin sürümüne bağlar ve bağımsızlığı sessizce bozar.

## Decision

**12 microservice** kullanıyoruz (10 domain + 2 cross-cutting), her biri **kendi DB'sine sahip
(DB-per-service)** ve servisler-arası iletişim yalnızca **gRPC + Kafka** ([ADR-003](./0003-grpc-protobuf-inter-service.md)):

| # | Servis | Sorumluluk |
|---|---|---|
| 1 | identity-service | Kullanıcı, rol, permission, auth/session |
| 2 | organization-service | Installation, tenant, okul, şube, sınıf |
| 3 | academic-service | Müfredat, ders programı, yoklama, ödev |
| 4 | assessment-service | Sınav, not, karne |
| 5 | counseling-service | PDR (rehberlik) — özel kategori veri |
| 6 | performance-service | Performans, gözlem, hedef |
| 7 | communication-service | Mesajlaşma, sohbet, duyuru |
| 8 | finance-service | Fatura, ödeme, borç, iade |
| 9 | file-service | Dosya metadata + RustFS adapter |
| 10 | audit-service | Merkezî audit log (cross-cutting) |
| 11 | compliance-service | DSAR, retention, anonymization |
| 12 | notification-service | Email/SMS/push (cross-cutting) |

**Paylaşılan kütüphane YOK.** Servisler arasında ortak util/DTO/base-class kütüphanesi oluşturulmaz;
tekrarlanan kod (örn. benzer bir mapper veya JWT doğrulama yardımcısı) **kabul edilir**. Ortak *sözleşme*
paylaşımı yalnızca **`.proto` şemaları** üzerinden yapılır (kod değil, kontrat). Yeni servisler ortak
koddan değil, **`service-template` iskeletinden** türetilir (kopyala-türet, bağla-değil).

## Consequences

- **Olumlu:** Her servis bağımsız deploy/evrim; bir servisin değişikliği diğerlerini derleme-zamanı
  kırmaz; sürüm-kilidi (dependency hell) yok; ekip bir servise odaklanabilir.
- **Olumsuz / bedel:** **Kod tekrarı** (aynı yardımcı birden çok serviste); ortak bir hata birden çok yerde
  düzeltilir; tutarlılık disiplin gerektirir.
- **Azaltıcı önlemler:** `service-template` iskeleti ortak *yapıyı* (hexagonal, config, health, logging)
  kopyalanabilir biçimde sağlar → tekrar "bağımlılık" değil "şablon" olur. Ortak davranış gerçekten
  kaçınılmazsa (örn. çok stabil bir kontrat) önce `.proto`, sonra gerekirse ayrı-versiyonlanan *opt-in*
  kütüphane ayrı ADR ile tartışılır.

## Alternatives Considered

- **Modüler monolit** — Tek deployable, modül sınırları içeride. → **Elendi:** Müşteri başına kurulum,
  bağımsız ölçekleme (yoklama peak'i vs. rehberlik) ve bounded-context izolasyonu ayrı deploy gerektiriyor.
- **Paylaşılan "common" kütüphanesi** — Ortak util/DTO/base entity tek kütüphanede. → **Elendi:** Tüm
  servisleri kütüphane sürümüne bağlar; "küçük bir değişiklik" 12 servisi yeniden sürümler → microservice
  bağımsızlığı fiilen kaybolur. Duplicate kodu bu bağımlılığa tercih ettik.
- **Daha az servis (birleştirme)** — Örn. akademik + sınav + performans tek serviste. → **Elendi:** Rehberlik
  (özel veri, ayrı erişim/audit), audit (cross-cutting) ve notification (adapter sürüleri) birleştirilince
  sınırlar bulanır; ayrıca bağımsız ölçekleme kaybolur.
- **Daha çok servis** — organization'ı 3-4 parçaya bölmek gibi. → **Elendi (şimdilik):** 2 kişilik ekipte
  gereksiz operasyon yükü; servis bölme kuralı ihtiyaç doğunca uygulanır.

## References

- [Domain Servisleri — 12 Microservice](../01-tenancy-and-domain-model/02-domain-services-overview.md)
- [Microservices Architecture](../02-architecture-patterns/01-microservices-architecture.md)
- [ADR-005: Hexagonal Architecture](./0005-hexagonal-architecture.md) — her servisin iç yapısı
- `campus/backend/service-template/` — servis türetme iskeleti
