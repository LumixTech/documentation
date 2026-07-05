---
title: "ADR-005: Hexagonal architecture standart yapısı"
description: Her microservice hexagonal (ports & adapters) yapıda; domain → application → adapter bağımlılık yönü.
sidebar_position: 5
---

# ADR-005: Hexagonal architecture standart yapısı

| Alan | Değer |
|---|---|
| **Status** | Accepted |
| **Tarih** | 2026-06-06 |
| **Deciders** | Lumix çekirdek ekip |
| **Sprint** | Sprint 0 |

## Context

Her microservice REST, gRPC ve Kafka gibi birden çok giriş/çıkış kanalına, PostgreSQL kalıcılığına ve
karmaşık domain kurallarına (yoklama revizyon penceresi, rehberlik erişim kuralları, ödeme state machine)
sahip. İç yapı için bir standart lazım ki:

- Domain kuralları **framework'ten bağımsız** ve hızlı unit-test edilebilir olsun.
- Bir adapter (REST↔gRPC) değişince domain kırılmasın.
- Validation'ın nerede yapıldığı (input vs invariant) net olsun.
- 12 servis **aynı** iç şablonu izlesin → geçiş yapan geliştirici kaybolmasın.

## Decision

Her microservice **Hexagonal Architecture (Ports & Adapters)** yapısını izler. Bağımlılık yönü **her zaman
içeriye**: `adapter → application → domain`. Modül ayrımı: `domain` (saf Java), `application`
(inbound/outbound port + use-case service), `adapter-rest / adapter-grpc / adapter-kafka /
adapter-persistence`, `bootstrap`. Kural CI'da **ArchUnit** testiyle otomatik denetlenir.

**Pragmatik yaklaşım:** `domain`'de Spring annotation'ı yasak; `application/service`'te Spring stereotype
(`@Service`, `@Transactional`) kabul. Bu standart `service-template` iskeletinde somutlaştırılmıştır
([ADR-004](./0004-microservice-topology-no-shared-lib.md)).

## Consequences

- **Olumlu:** Domain saf Java → Spring context'siz, saniyenin altında unit test; adapter değişimi core'u
  kırmaz; port'lar mock'lanabilir; validation katmanlaması net (adapter=input, domain=invariant); tüm
  servisler tek şablon.
- **Olumsuz / bedel:** Daha çok dosya/modül (port + adapter ayrımı, mapper'lar); yeni geliştirici için
  öğrenme eğrisi; küçük CRUD servis için "fazla" gelebilir.
- **Azaltıcı önlemler:** Pragmatik gevşetme (domain'de JPA'ya izin verilebilir, mapper boilerplate'i
  minimize); ArchUnit ile kural otomatik korunur; `service-template` ile yapı hazır gelir.

## Alternatives Considered

- **Klasik katmanlı (Controller → Service → Repository)** — Spring'in default'u, en az dosya. → **Elendi:**
  Domain framework'e ve DB'ye doğrudan bağlanır; test için Spring context gerekir; adapter değişimi domain'i
  kırar; validation katmanları belirsiz.
- **Onion Architecture** — Hexagonal'e çok yakın, katman halkaları. → **Elendi:** Kavramsal fark yok;
  "port/adapter" terminolojisi ekip iletişiminde daha yerleşik.
- **Clean Architecture (Uncle Bob)** — Hexagonal'in genellemesi (use case/interactor/presenter). → **Elendi:**
  Aynı özde; "presenter/interactor" terminolojisi Spring projelerinde fazladan geliyor, pratik hexagonal
  tercih edildi.

## References

- [Hexagonal Architecture (Ports & Adapters)](../02-architecture-patterns/03-hexagonal-architecture.md)
- [Domain-Driven Design](../02-architecture-patterns/02-domain-driven-design.md)
- `campus/backend/service-template/` — hexagonal iskeletin referans uygulaması
