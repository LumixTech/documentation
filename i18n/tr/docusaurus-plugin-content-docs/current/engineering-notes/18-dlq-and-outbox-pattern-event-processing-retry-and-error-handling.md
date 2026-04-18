---
title: DLQ ve Outbox Pattern: Event İşleme, Retry ve Hata Yönetimi
description: Outbox relay, retry stratejisi, DLQ davranışı, replay ve idempotent consumer tasarımı için uçtan uca mimari rehber.
sidebar_position: 5
---

## Giriş

Dağıtık sistemlerde veri akışı tek bir transaction içinde tamamlanmaz. Bu nedenle şu hatalar beklenen durumlardır:

- DB'ye yazıldı ama event publish edilemedi,
- event publish edildi ama consumer işleyemedi,
- consumer işledi ama side-effect tamamlanmadan sistem düştü,
- aynı event birden fazla kez işlendi.

Doğru tasarım olmazsa sessiz veri kaybı, duplicate işlem ve sistemler arası state divergence ortaya çıkar.

## Temel Kavramlar

- Outbox Pattern: business veri ve integration event'in aynı DB transaction içinde yazılması.
- Relay (Publisher): outbox kayıtlarını okuyup broker'a publish eden süreç.
- Retry: geçici hatalarda kontrollü tekrar deneme.
- Dead Letter Queue (DLQ): işlenemeyen mesajların ayrıldığı queue/topic.
- Replay: DLQ mesajlarının fix sonrası ana akışa yeniden verilmesi.
- Idempotency: aynı event tekrar işlense de sonucun değişmemesi.

## Problem Senaryosu

```text
Order oluşturuldu
-> DB'ye yazıldı
-> Event publish edilecekti
-> Network hatası
```

Sonuç:

- order DB'de var,
- event broker'a düşmedi,
- read model güncellenmedi,
- sistem tutarsız hale geldi.

## Yaklaşım / Tasarım

### 1) Outbox Pattern

```text
BEGIN TX
  INSERT INTO orders
  INSERT INTO outbox_events
COMMIT
```

Avantaj:

- business write ve event write atomik olur,
- event kaybı engellenir.

### 2) Outbox Relay

```text
outbox_events
   ↓
relay process
   ↓
Kafka publish
   ↓
mark as published
```

### 3) Consumer Flow

```text
Kafka topic
   ↓
Consumer
   ↓
Read DB update
```

## Sektör Standardı Uçtan Uca Akış

```text
1. Command gelir
2. orders + outbox aynı transaction'da yazılır
3. COMMIT

4. Relay outbox'tan event alır
   CASE:
   - Publish başarısız -> retry
   - Publish başarılı -> devam

5. Event Kafka'ya gider

6. Consumer event'i alır
   CASE:
   - Başarılı -> commit
   - Geçici hata -> retry
   - Kalıcı hata -> DLQ

7. DLQ akışı:
   - alert
   - inceleme
   - fix
   - replay
```

## DLQ Detaylı Davranış

### Mesaj ne zaman DLQ'ya gider?

Sadece şu durumda:

> Consumer mesajı alır, işleme başarısız olur ve retry sayısı biter.

### DLQ'ya gitmeyen durumlar

- consumer kapalıysa ve poll etmiyorsa,
- broker/network geçici olarak erişilemiyorsa,
- mesaj topic backlog'unda bekliyorsa.

### DLQ akışı

```text
consume
   ↓
error
   ↓
retry (n kez)
   ↓
fail
   ↓
DLQ
```

### DLQ ne yapmaz?

- upstream business transaction rollback yapmaz,
- business işlemi otomatik iptal etmez,
- kendi başına `event_cancelled` üretmez.

DLQ sadece şunu söyler: "Bu mesaj işlenemedi."

## Retry ve Poison Message

Retry, geçici hatalar için kullanılır:

- DB timeout,
- network glitch,
- lock contention.

Poison message ise retry ile düzelmeyecek mesajdır:

- schema mismatch,
- zorunlu field eksikliği,
- geçersiz data contract.

Poison message DLQ'ya yönlendirilmelidir.

## Replay Mekanizması

```text
DLQ
  ↓
manual / tool replay
  ↓
consumer tekrar işler
```

Replay riski:

- aynı event tekrar işlenebilir,
- duplicate üretilebilir.

Bu yüzden idempotency zorunludur.

## Idempotency Stratejileri

### Event ID kontrolü

```text
if eventId processed:
   skip
```

### Business key kontrolü

```text
if orderId already exists:
   do not recreate
```

### Upsert yaklaşımı

```sql
INSERT ... ON CONFLICT DO NOTHING
```

## Teknik ve Business Failure Ayrımı

Technical failure örnekleri:

- exception,
- timeout,
- parsing error.

Aksiyon: retry + DLQ.

Business failure örnekleri:

- stok yok,
- ödeme reddedildi.

Aksiyon: compensation event, örneğin:

```text
OrderCancelled
PaymentReversed
```

## Kritik Trade-off'lar

| Problem | Çözüm |
| --- | --- |
| Event kaybı | Outbox |
| Geçici hata | Retry |
| Poison message | DLQ |
| Duplicate işleme | Idempotency |
| State mismatch | Replay |

## Sonuç

Outbox + DLQ birlikte şunları sağlar:

- event'ler sessizce kaybolmaz,
- ana akış sonsuza kadar bloklanmaz,
- hatalar izole edilir ve yeniden işlenebilir hale gelir.

Bununla birlikte operasyon modeli şuna dönüşür:

> eventual consistency + retry + idempotent consumer.

## Sözlük

- Outbox Pattern
- Relay
- Retry
- DLQ
- Replay
- Idempotency
- Poison Message
- Eventual Consistency
- At-least-once delivery

## Araştırma Keywordleri

- `outbox pattern kafka spring boot`
- `dead letter queue best practices`
- `kafka retry dlq architecture`
- `idempotent consumer design`
- `event driven error handling`
- `poison message handling kafka`

## Bizim Notlarımız / Özel Vurgular

- Outbox event publish kaybını önler.
- DLQ business cancel mekanizması değildir.
- Retry her zaman DLQ'dan önce gelir.
- Replay, root-cause fix sonrası yapılmalıdır.
- Idempotency olmadan replay risklidir.
- At-least-once mimaride duplicate event normaldir.
