---
title: "ADR-003: gRPC + Protobuf servisler-arası iletişim"
description: Senkron inter-service iletişim gRPC + Protobuf; async iletişim ise Kafka (ayrı karar).
sidebar_position: 3
---

# ADR-003: gRPC + Protobuf servisler-arası iletişim

| Alan | Değer |
|---|---|
| **Status** | Accepted |
| **Tarih** | 2026-06-06 |
| **Deciders** | Lumix çekirdek ekip |
| **Sprint** | Sprint 0 |

## Context

12 microservice birbiriyle konuşacak ([ADR-004](./0004-microservice-topology-no-shared-lib.md)). İki tür
iletişim var: **senkron sorgu-yanıt** (örn. "bu sınıf hangi tenant'a ait?") ve **asenkron olay** (örn.
"yoklama alındı"). Bu ADR **senkron** kanalı kapsar; async için Kafka ayrı bir karardır.

Güçler: **schema disiplini** (servisler bağımsız deploy edildiği için sözleşme sıkı olmalı), **kod
üretimi** (DTO'ların elle senkronizasyonu hata kaynağı), **performans** (dahili çağrılar sık), **çok dillilik**
(ileride farklı dilde bir servis olabilir — örn. Rust video servisi).

## Decision

Servisler-arası **senkron** iletişimde **gRPC + Protobuf** kullanıyoruz. Proto dosyaları sözleşmenin tek
kaynağıdır; Java stub'ları `protobuf-gradle-plugin` ile üretilir ([ADR-006](./0006-gradle-kotlin-dsl-build-tool.md)).
Protobuf ayrıca **async event'lerin de** schema formatıdır (Kafka + Apicurio Registry) — böylece tüm sistemde
**tek schema dili** olur.

## Consequences

- **Olumlu:** Sıkı, versiyonlanabilir sözleşme; HTTP/2 üzerinden düşük gecikme ve binary verimlilik;
  code-gen ile client/server tipleri otomatik senkron; streaming desteği; tek schema dili (sync + async).
- **Olumsuz / bedel:** Tarayıcıdan doğrudan çağrılamaz (gRPC-Web/gateway gerekir) → dış API için REST katmanı
  ayrı; `.proto` + codegen build karmaşıklığı; insan-okunur debug (curl) daha zor.
- **Azaltıcı önlemler:** Dışa açık API'ler REST adapter'ı üzerinden ([Hexagonal](./0005-hexagonal-architecture.md)
  inbound adapter); şema uyumluluğu **BACKWARD** modda; codegen tek plugin ile standardize.

## Alternatives Considered

- **REST + JSON (OpenAPI)** — Yaygın, insan-okunur, tooling bol. → **Elendi (iç iletişim için):** Şema
  disiplini gevşek, JSON verimsiz, DTO senkronizasyonu elle. Yine de **dışa açık** API için kullanılıyor
  (iç ≠ dış). 
- **GraphQL** — Esnek sorgu, tek endpoint. → **Elendi:** Servisler-arası RPC için fazla; asıl faydası
  client-driven aggregation, bu bir gateway/BFF konusu, iç RPC değil.
- **Apache Thrift** — gRPC'ye benzer, code-gen + binary. → **Elendi:** Topluluk/momentum ve Spring/K8s
  ekosistem entegrasyonu gRPC'de daha güçlü.
- **Avro RPC** — Schema-tabanlı. → **Elendi:** Avro Kafka/analitik dünyasında güçlü ama RPC ergonomisi ve
  Spring entegrasyonu gRPC kadar olgun değil; ayrıca schema dilini Protobuf'ta birleştirme tercihi.
- **SOAP/XML-RPC** — → **Elendi:** Ağır, eski, verimsiz.

## References

- [gRPC Service Communication](../03-backend/03-grpc-service-communication.md)
- [Domain Servisleri — 12 Microservice](../01-tenancy-and-domain-model/02-domain-services-overview.md)
- [Teknoloji Kararları — Tek Sayfa Özet](../00-overview/02-technology-stack-decisions.md)
