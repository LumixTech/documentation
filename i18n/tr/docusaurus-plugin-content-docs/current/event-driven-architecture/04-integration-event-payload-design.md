---
title: "Integration Event Payload Tasarımı: Minimum, Stabil ve Anlamlı Veri"
description: Integration event payload'unun metadata, idempotency, traceability, replay ve backward compatibility ihtiyaçlarıyla nasıl tasarlanacağını açıklar.
sidebar_position: 6
---

## Giriş

Integration Event, her consumer isteğine göre şişirilmemelidir. Producer'ın dış dünyaya bilinçli olarak açıkladığı stabil iş gerçeğini temsil etmelidir.

Tasarım sorusu sadece "mevcut consumer ne istiyor?" değildir. Daha doğru soru şudur: "Dış dünyaya uzun ömürlü contract olarak hangi iş gerçeğini açıklamak istiyoruz?"

## Metadata Alanları

Integration Event operasyonel güvenlik için genellikle metadata taşır:

```json
{
  "eventId": "evt-123",
  "eventType": "OrderPlaced",
  "version": 1,
  "occurredAt": "2026-04-25T10:15:30Z",
  "traceId": "trace-abc",
  "source": "order-service"
}
```

Bu alanlar şunları destekler:

- idempotency,
- tracing,
- replay,
- debugging,
- schema evolution,
- auditability.

## Business Payload

Payload, yayımlanan iş olgusunu anlamak için gerekli alanları içermelidir.

Örnek:

```json
{
  "orderId": "ord-456",
  "customerId": "cus-789",
  "orderLines": [
    {
      "productId": "prd-1",
      "quantity": 2,
      "unitPrice": "49.95"
    }
  ],
  "totalAmount": "99.90",
  "currency": "TRY"
}
```

Bu tam bir `OrderEntity` değildir. Bu, siparişin oluşturulduğunu duyuran public mesajdır.

## Idempotency

Consumer aynı event'i birden fazla kez güvenle işleyebilmelidir.

Yaygın strateji:

```text
if eventId already_processed:
  skip
else:
  process_event
  mark eventId as processed
```

At-least-once delivery'de duplicate processing normaldir. Ciddi asenkron sistemlerde idempotency opsiyonel değildir.

## Replay ve Debugging

Consumer fix'i, projection rebuild veya DLQ recovery sonrası event'ler replay edilebilir. Bu yüzden payload daha sonra işlendiğinde anlamını koruyacak kadar stabil context içermelidir.

Bu, event her şeyi taşımalı demek değildir. Event, iş olgusunu unstable internal state'e ihtiyaç duymadan yorumlatacak kalıcı bilgiyi taşımalıdır.

## Consumer-Specific Data

Tek event'i her consumer için torba mesaja çevirmekten kaçın:

```text
OrderPlaced event:
  + notificationTemplateName
  + analyticsCampaignCode
  + warehousePickPriority
  + crmSegmentSnapshot
```

Bu yaklaşım ownership'i belirsizleştirir ve producer'ı downstream view'ların sahibi yapar. Consumer'ın özel ihtiyacı varsa ayrı event, consumer-side lookup, read API veya dedicated projection daha doğru olabilir.

## Versioning Kuralları

- Mümkünse optional field ekle.
- Mevcut field'ların anlamını değiştirme.
- `eventType` ve `version` alanlarını explicit tut.
- Required ve optional field'ları dokümante et.
- Migration boyunca eski ve yeni consumer'ları birlikte destekle.

## Teknik Terimler Sözlüğü

- Event Metadata
- Event ID
- Event Type
- Version
- Idempotency
- Replay
- Traceability
- Payload Design
- Consumer-specific View

## Bizim Notlarımız / Özel Vurgular

- Integration consumer'ların ihtiyaç duyduğu veriyi vermek doğrudur.
- Fakat event sadece mevcut consumer'ın isteğine göre şekillenmemelidir.
- Payload, producer'ın dış dünyaya açıklamak istediği stabil iş gerçeğini temsil etmelidir.

## Araştırma Keywordleri

- `integration event payload best practices`
- `event metadata idempotency`
- `event versioning backward compatibility`
- `event payload minimal design`
- `event driven architecture payload bloat`

## Sonuç

Integration Event, her consumer'ın istediği her şeyi taşıyan torba mesaj olmamalıdır. Dış dünyaya açıklanacak stabil iş gerçeğini yeterli metadata ve anlamlı payload ile duyurmalıdır.
