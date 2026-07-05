---
title: Domain Event ve Integration Event Ayrımı
description: İç domain event'leri ile dış sistemlere yayımlanan integration event kontratlarının neden ayrı modellenmesi gerektiğini açıklar.
sidebar_position: 3
---

## Giriş

Domain Event ile Integration Event çoğu zaman aynı şey sanılır. Bu kısa yol coupling üretir çünkü iç model ile dış iletişim kontratı birlikte değişmeye başlar.

Daha sağlıklı tasarım, domain içi gerçeği dış dünyaya verilen sözleşmeden ayırmaktır.

## Temel Fark

Domain Event:

- bounded context içinde yaşar,
- iç business anlamı temsil eder,
- domain modeli değiştikçe değişebilir,
- genellikle aggregate veya domain service tarafından üretilir.

Integration Event:

- başka sistemlere yayımlanır,
- stabil public contract gibi davranır,
- backward compatibility gözetilerek evrilir,
- external consumer'lar için tasarlanır.

## Kafka Event'i Public API Gibidir

Kafka'ya yayımlanan event, public API gibi düşünülmelidir. Consumer servisler bu event etrafında kod, storage, metric, alert ve workflow geliştirir.

Bu yüzden integration event değişikliği, internal object içindeki bir field'ı rename etmekle aynı şey değildir. Downstream sistemleri kırabilir.

## Mapping Sınırı

Yaygın yaklaşım, domain event'i application veya outbox katmanında integration event'e map etmektir.

```text
Order aggregate
  -> OrderPlaced domain event
  -> application/outbox mapper
  -> OrderPlacedIntegrationEvent
  -> Kafka
```

Bu mapping, dış kontratı stabil tutarken iç modelin refactor edilmesine alan açar.

## Versioning ve Compatibility

Integration Event dikkatli evrilmelidir:

- var olan field'ı rename etmek yerine mümkünse optional field ekle,
- field anlamını değiştirme,
- explicit `version` metadata'sı kullan,
- migration boyunca eski consumer'ların çalışmasını koru,
- schema ve semantic değişiklikleri dokümante et.

## Anti-Pattern

```text
aggregate emits OrderPlaced
  -> directly serialize domain event
  -> publish to Kafka
  -> consumers depend on internal fields
```

Bu yaklaşım, internal state etrafında kazara public contract oluşturur.

## Teknik Terimler Sözlüğü

- Integration Event
- Public Contract
- Bounded Context
- Backward Compatibility
- Event Contract
- External Consumer
- Internal Model
- Event Versioning

## Bizim Notlarımız / Özel Vurgular

- Dış dünyaya yayımlanan mesaj çoğu zaman raw domain event değil, integration event olmalıdır.
- Bounded context dışına çıkacak event ayrı contract olarak tasarlanmalıdır.
- Domain Event doğrudan Kafka'ya basılırsa iç model external consumer'lara sızar.

## Araştırma Keywordleri

- `domain event vs integration event`
- `integration event contract`
- `bounded context events`
- `event contract versioning`
- `internal events external events`

## Sonuç

Her Integration Event bir event'tir. Ama her Domain Event, Integration Event değildir. İç model event'i ile dış dünyaya yayımlanan mesaj kontratı ayrı tasarım kararlarıdır.
