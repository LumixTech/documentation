---
title: "Domain Event'in Anatomisi: Sistemin İç Gerçeği"
description: DDD domain event'lerinin entity sızdırmadan ve dış sistem kontratına dönüşmeden nasıl modellenmesi gerektiğini açıklar.
sidebar_position: 2
---

## Giriş

Event-driven mimaride her mesaj aynı türden değildir. Domain Event, domain modeli içinde gerçekleşmiş anlamlı bir iş olgusunu temsil eder.

Tehlikeli kısa yol şudur: "Sistemde bir şey olduysa event fırlatırım." Bu yaklaşım genellikle gürültülü mesajlara, kararsız kontratlara ve kazara coupling'e yol açar.

## Temel Kavram

Domain Event geçmiş zamanda ifade edilir çünkü tamamlanmış bir iş gerçeğini kaydeder:

- `OrderPlaced`
- `PaymentAuthorized`
- `SeatReserved`
- `CustomerRegistered`

Bu isimler teknik bildirim değildir. Bounded context'in iş dili içinde anlamı olan gerçekleri anlatır.

## Command ve Domain Event Farkı

Command davranış ister:

```text
PlaceOrder
AuthorizePayment
ReserveSeat
```

Domain Event ise gerçekleşmiş olayı bildirir:

```text
OrderPlaced
PaymentAuthorized
SeatReserved
```

Bu ayrım önemlidir çünkü command reddedilebilir, fakat event model içinde artık doğru kabul edilen bir gerçeği ifade etmelidir.

## Aggregate İçinden Doğan Event

DDD'de domain event çoğu zaman aggregate invariant'larını koruduktan sonra aggregate içinden doğar.

```text
Order.place(customer_id, lines):
  validate_order_lines(lines)
  validate_customer_can_order(customer_id)

  status = PLACED
  record_event(OrderPlaced(order_id, customer_id, occurred_at))
```

Aggregate, iş geçişinin geçerli olup olmadığına karar verir. Event bu geçerli geçişin sonucudur; validation'ın yerine geçmez.

## Payload Şekli

Domain Event entity'nin tamamını taşımamalıdır. İş olgusunu anlatan anlamlı minimum bilgiyi taşımalıdır.

Kötü tasarım:

```text
OrderPlaced(OrderEntity order)
```

Daha iyi tasarım:

```text
OrderPlaced(
  orderId,
  customerId,
  occurredAt
)
```

Event, entity snapshot'ı değildir. Event bir iş cümlesidir.

## Sektör Standardı / Best Practice

- Domain event isimlerini geçmiş zamanda kullan.
- Event'i anlamlı aggregate state transition sonucunda üret.
- Payload'u iş anlamına odaklı tut.
- Persistence nesneleri, lazy relation'lar, framework annotation'ları ve internal state detaylarını event'e koyma.
- Domain event'i açıkça integration event'e map etmeden dış sistem kontratı gibi düşünme.

## Yaygın Hata

Domain Event'i doğrudan Kafka'ya publish etmek, iç domain kararlarını dış sistem kontratına dönüştürür. Böyle olunca external consumer'lar private model detaylarına bağımlı hale gelir ve refactor maliyeti artar.

## Teknik Terimler Sözlüğü

- Domain Event
- Aggregate
- Command
- Business Invariant
- Domain Model
- Entity Leakage
- Internal Event
- OccurredAt

## Bizim Notlarımız / Özel Vurgular

- Tüm entity'yi event içinde paylaşmak tasarım kokusudur.
- Domain Event açık bir dış kontrat kararına dönüştürülmeden doğrudan Kafka'ya basılmamalıdır.
- Servisler arası iş birliğinde çoğu zaman command dağıtmak yerine integration event yayımlamak tercih edilir.

## Araştırma Keywordleri

- `domain event ddd`
- `domain event vs command`
- `aggregate domain event`
- `domain events best practices`
- `event driven architecture domain event`

## Sonuç

Domain Event, domain içinde gerçekleşmiş anlamlı bir iş olgusudur. Fakat bu, otomatik olarak Kafka'ya basılacak dış sistem kontratı olduğu anlamına gelmez.
