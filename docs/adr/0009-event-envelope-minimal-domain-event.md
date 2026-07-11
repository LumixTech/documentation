---
title: "ADR-009: Event envelope — minimal domain event + adapter'da envelope"
description: Domain event minimal (occurredAt + eventType + iş alanları); integration event envelope'u (eventId, version, source) ve tenant_id/trace_id header'ları adapter'da kurulur. Kimliği sözleşmeye koyma (Seçenek B) ve zengin domain=integration event elendi.
sidebar_position: 9
---

# ADR-009: Event envelope — minimal domain event + adapter'da envelope

| Alan | Değer |
|---|---|
| **Status** | Accepted |
| **Tarih** | 2026-07-11 |
| **Deciders** | Lumix çekirdek ekip |
| **Sprint** | Sprint 1 |

## Context

Servisler async iletişimde Kafka'ya **integration event** yayınlar (Outbox ile). Bir **domain event**
sözleşmesi lazım. İlk hali yalnızca `occurredAt` + `eventType` taşıyordu — oysa
[payload tasarımı dokümanı](../event-driven-architecture/04-integration-event-payload-design.md) zorunlu bir
envelope tanımlıyor: `eventId`, `eventType`, `version`, `occurredAt`, `traceId`, `source`. Ayrıca Lumix
**multi-tenant**: her event'te `tenant_id` gerekli ve
[tenancy §9.6](../01-tenancy-and-domain-model/01-installation-tenant-scope.md) bunun Kafka **header**'ında
taşınmasını söylüyor (tüketici body'yi deserialize etmeden filtrelesin).

Kritik soru: envelope alanları (`eventId`, `version`, `traceId`, `source`, `tenant_id`) **nerede yaşar** —
domain event sözleşmesinde mi, yoksa yayınlayan adapter'da mı? Bu, domain katmanının altyapıdan ne kadar
bağımsız kalacağını ([ADR-005](./0005-hexagonal-architecture.md)) belirliyor.

## Decision

**Seçenek A:** domain event **MINIMAL** kalır (`occurredAt` + `eventType` + iş alanları; framework-bağımsız,
`domain` modülünde). Integration event **envelope'u yayınlayan adapter'da (Kafka publisher / Outbox relay)**
kurulur:

- `eventId` (UUID v7), `version` (şema evrimi), `source` (servis adı) → envelope gövdesinde.
- `tenant_id` ve `trace_id` → **gövdede değil, Kafka header'ında** (MDC'den), tüketici body açmadan
  filtrelesin/route etsin.
- `eventId` **idempotency/dedup** anahtarıdır; Outbox gelince **outbox-insert'te bir kez** üretilir ve
  retry'da **aynı kalır** (yoksa dedup çalışmaz).

## Consequences

- **Olumlu:** Domain saf kalır (hexagonal bağımlılık yönü korunur, ArchUnit yeşil); envelope tek yerde
  kurulduğu için tüm event'lerde tutarlı; `tenant_id` header → tüketici deserialize etmeden route/filter;
  stabil `eventId` ile at-least-once teslimatte güvenli idempotency.
- **Olumsuz / bedel:** Adapter, publish anında request context'ine (MDC) bağımlı; concrete event/Outbox
  henüz yokken envelope kısmen iskelet (payload sonra dolacak).
- **Azaltıcı önlemler:** Envelope bir `record` + tek publisher'da merkezî; publisher'da açık **ŞABLON UYARISI**
  (gerçek serviste Outbox); `eventId` üretimi Outbox'a taşınınca dedup semantiği netleşir.

## Alternatives Considered

- **Seçenek B — `eventId` + `version` domain event sözleşmesinde** — Kimlik ve şema-versiyonu event'in özüne
  ait; adapter yalnızca `traceId`/`source` ekler. → **Elendi (şimdilik):** Argüman geçerli ama her concrete
  event bu alanları üretmek zorunda (boilerplate) ve domain'e altyapı sızar; A daha temiz. İhtiyaç doğarsa
  ayrı ADR ile yeniden değerlendirilebilir (bu ADR *Superseded* edilmeden).
- **Zengin domain event = integration event (tek tip, hepsi domain'de)** — Tek sınıf, ekstra map'leme yok.
  → **Elendi:** Domain'i wire-sözleşmesine bağlar; şema evrimi domain'i kırar; bounded-context sınırı bulanır
  (domain event iç, integration event dış sözleşmedir).
- **`tenant_id`'yi payload gövdesinde taşımak** — Tek yerde, header yönetimi yok. → **Elendi:** Tüketici her
  mesajı deserialize etmeden tenant'a göre filtreleyemez/route edemez; header standardı (tenancy §9.6) tercih
  edildi.

## References

- [Integration Event Payload Design](../event-driven-architecture/04-integration-event-payload-design.md) — envelope alanları
- [Installation / Tenant / Scope §9.6](../01-tenancy-and-domain-model/01-installation-tenant-scope.md) — tenant_id header'da
- [ADR-005: Hexagonal Architecture](./0005-hexagonal-architecture.md) — domain saflığı
- Outbox Pattern (02-architecture-patterns/06) — event publish garantisi
- `campus/backend/organization-service/.../KafkaDomainEventPublisher` + `IntegrationEventEnvelope`
