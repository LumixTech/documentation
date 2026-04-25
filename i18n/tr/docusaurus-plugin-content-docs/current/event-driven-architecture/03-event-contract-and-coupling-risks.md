---
title: "Event Contract ve Coupling Riskleri: Entity Leakage Problemi"
description: Entity'yi event payload'u içinde paylaşmanın persistence leakage, semantic coupling, contract instability ve veri sızıntısı risklerini açıklar.
sidebar_position: 4
---

## Giriş

`OrderPlaced(OrderEntity order)` gibi tasarımlar ilk bakışta pratik görünür. Mapping azalır ve consumer'ın ihtiyaç duyabileceği tüm alanlar hazırmış gibi hissedilir.

Pratikte bu yaklaşım persistence modelini public contract haline getirir.

## Entity Leakage Neden Tehlikelidir?

Entity event payload'una konduğunda consumer servisler aslında public olmaması gereken detaylara bağımlı hale gelebilir:

- database identifier'ları,
- internal status isimleri,
- lazy relation'lar,
- payment token,
- fraud score,
- audit field'ları,
- internal flag'ler,
- persistence framework yapısı.

Event artık iş mesajı olmaktan çıkar, serialize edilmiş internal object haline gelir.

## Persistence Model Leakage

Persistence entity'leri storage ve transaction tutarlılığı için optimize edilir. Event contract ise servisler arası iletişim için optimize edilir.

Bu iki model farklı sebeplerle değişir. İkisi birleştiğinde her persistence refactor'ı potansiyel integration değişikliğine dönüşür.

## Contract Instability

Consumer'lar `OrderEntity` şekline bağımlıysa entity değişikliği contract değişikliğine dönüşür:

- field rename deserialization'ı bozabilir,
- relation bölünmesi consumer logic'i bozabilir,
- enum değişikliği business yorumunu değiştirebilir,
- field kaldırmak reporting veya notification akışlarını kırabilir.

Internal refactor zorlaşır çünkü dış sistemler internal shape'e bağlanmıştır.

## Semantic Coupling

Semantic coupling compile-time coupling'den daha sinsi olabilir. Consumer producer'ın kodunu import etmeyebilir, ama producer'ın private anlamlarına güvenebilir.

Örnek:

```text
Consumer varsayımı:
  order.status = "WAITING_PAYMENT" ise müşteri bildirimi gönderilebilir
```

Producer daha sonra lifecycle'ı değiştirdiğinde schema valid kalsa bile consumer yanlış davranabilir.

## Overexposure ve Payload Bloat

Tüm entity'yi paylaşmak genellikle gereksiz veriyi dışarı açar. Bu da şu riskleri üretir:

- privacy ve compliance riski,
- daha büyük mesajlar,
- zor schema evolution,
- belirsiz ownership,
- debugging gürültüsü.

Event, bildirdiği iş olgusu için anlamlı minimum veriyi taşımalıdır.

## Daha İyi Şekil

Kötü:

```text
OrderPlaced(OrderEntity order)
```

Daha iyi:

```json
{
  "eventId": "evt-123",
  "eventType": "OrderPlaced",
  "version": 1,
  "occurredAt": "2026-04-25T10:15:30Z",
  "orderId": "ord-456",
  "customerId": "cus-789",
  "totalAmount": "149.90",
  "currency": "TRY"
}
```

## Teknik Terimler Sözlüğü

- Persistence Model Leakage
- Contract Instability
- Semantic Coupling
- Overexposure
- Payload Bloat
- Consumer Coupling
- Internal State
- Refactorability

## Bizim Notlarımız / Özel Vurgular

- Tüm entity'yi paylaşmak bu başlığın ana anti-pattern'idir.
- `OrderEntity` gibi JPA/persistence nesneleri event payload'una konmamalıdır.
- Lazy relation, internal status, payment token, fraud score gibi detaylar public event'e taşınmamalıdır.

## Araştırma Keywordleri

- `event payload entity leakage`
- `event driven architecture coupling`
- `semantic coupling events`
- `integration event payload design`
- `event contract anti patterns`

## Sonuç

Event, entity'nin kılık değiştirmiş hali olmamalıdır. Event, iş açısından anlamlı ve minimum payload taşıyan stabil bir mesaj olmalıdır.
