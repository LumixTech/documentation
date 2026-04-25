---
title: "Observability: Log, Metric, Trace ve Correlation ID"
description: Structured logging, metrics, traces, correlation-id, tenant-id ve request context propagation kuralları.
sidebar_position: 1
---

## Giriş

Observability, production sistemin ne yaptığını sistemin ürettiği sinyallerden anlayabilme yeteneğidir. Log, metric ve trace farklı soruları cevaplar. Birlikte kullanıldığında ekip "bir şey bozuk" noktasından "nerede ve neden bozuk" noktasına gidebilir.

Multi-tenant üründe observability request context'i de tutarlı taşımalıdır. Minimum olarak her request `correlation-id` ve `tenant-id` bilgisini log, metric, trace, async job ve downstream call boyunca korumalıdır.

## Neden Önemli

Production incident sırasında eksik context pahalıdır:

- log hata gösterir ama request path'i bağlamaz,
- metric latency gösterir ama etkilenen tenant'ı göstermez,
- trace span gösterir ama application log ile bağlanamaz,
- async job orijinal request kimliğini kaybeder,
- support sorunun tek user, tek tenant veya tüm sistem olup olmadığını söyleyemez.

Observability sadece platform özelliği değil, ürün güvenilirliği gereksinimidir.

## Temel Kavramlar

- Log: gerçekleşen olaya ait event-level kayıt.
- Metric: zaman içinde ölçülen sayısal değer.
- Trace: request veya workflow'un component'ler arası uçtan uca gösterimi.
- Span: trace içindeki tek operasyon.
- Correlation ID: log, trace ve application event'leri bağlayan stabil identifier.
- `tenant-id`: multi-tenant operasyonlar için tenant boundary context'i.
- Structured logging: yalnızca text değil parse edilebilir field'lar içeren log.
- Context propagation: identifier'ların process, thread, queue ve service boundary boyunca taşınması.

## Gerçek Ürün Geliştirmede Problem

Sık hata local observability'dir:

```text
Service A request_id loglar
Service B trace_id loglar
Worker hiçbir şey loglamaz
Database metric'lerinde tenant dimension yoktur
```

Her component tek başına observable görünür ama workflow uçtan uca observable değildir.

Tenant-aware ürünlerde ekip hızlıca şunları cevaplamalıdır:

- Bu tenant-specific problem mi?
- Bir modül mü yavaş, tüm request path mi?
- Async event API response sonrası devam etti mi?
- Background failure hangi user aksiyonundan doğdu?

## Yaklaşım / Tasarım

### Zorunlu Request Context

Her inbound request şunları taşımalıdır:

- `correlation-id`
- authenticated veya resolved ise `tenant-id`
- authenticated ise `user-id`
- `request-path`
- `request-method`
- privacy policy'ye uygun `client-ip`
- ilgili yerde `device-session-id`

### Propagation Kuralı

```text
Inbound HTTP
  -> correlation-id resolve et veya üret
  -> tenant-id bilgisini auth/context üzerinden resolve et
  -> logging MDC/context içine ekle
  -> trace attribute olarak ekle
  -> downstream HTTP header'larına taşı
  -> queue message metadata içine ekle
  -> kritik aksiyonda audit log'a ekle
```

Önerilen header isimleri:

```text
X-Correlation-Id
X-Tenant-Id
traceparent
```

`X-Tenant-Id` client'tan geldi diye doğrudan güvenilmez; authenticated user/session context ile doğrulanmalıdır.

### Sinyal Sorumlulukları

| Sinyal | En iyi cevapladığı konu | Zayıf olduğu konu |
| --- | --- | --- |
| Logs | olay ve hata açıklaması | aggregate trend analizi |
| Metrics | alerting ve trend | detaylı root cause |
| Traces | cross-component latency ve causality | uzun vadeli high-cardinality analytics |
| Audit logs | compliance ve hassas aksiyon geçmişi | debug-level diagnostics |

## Sektör Standardı / Best Practice

- Stabil field isimleriyle structured log üret.
- OpenTelemetry-compatible context propagation kullan.
- Metric cardinality'yi kontrol altında tut.
- Logları trace/correlation identifier ile bağla.
- Raw personal data veya secret'ları loglama.
- Async job ve scheduled workflow içine request context taşı.
- `tenant-id` bilgisini free-form client input değil controlled context field olarak ele al.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
handle_inbound_request(request):
  correlation_id = request.header('X-Correlation-Id') or generate_uuid()
  tenant_id = resolve_tenant_from_authenticated_context(request)

  context.set('correlation-id', correlation_id)
  context.set('tenant-id', tenant_id)

  log.info('request.started', fields=context.safe_fields())
  trace.current_span.set_attribute('correlation-id', correlation_id)
  trace.current_span.set_attribute('tenant-id', tenant_id)

  response = next_handler(request)
  response.header('X-Correlation-Id', correlation_id)
  return response
```

Async propagation:

```text
publish_message(topic, payload):
  metadata = {
    correlation_id: context.get('correlation-id'),
    tenant_id: context.get('tenant-id'),
    traceparent: trace.current_context()
  }
  broker.publish(topic, payload, metadata)
```

## Bizim Notlarımız / Takım Kararları

- Tüm request'ler `correlation-id` taşımalıdır.
- Tüm tenant-scoped operasyonlar doğrulanmış `tenant-id` taşımalıdır.
- Log, metric, trace ve audit entry field isimleri tutarlı olmalıdır.
- Async workflow context'i kaybetmemeli, kopuk observability adaları üretmemelidir.
- Bu doküman [OpenTelemetry ve Tracing Stratejisi](./opentelemetry-tracing-strategy) ile birlikte ele alınır.

## Harici Referanslar

- OpenTelemetry: [Documentation](https://opentelemetry.io/docs/)

## Sözlük

- Correlation ID: component'ler arası event'leri bağlayan identifier.
- `tenant-id`: isolation ve diagnostics için tenant context'i.
- Trace: dağıtık request veya workflow path'i.
- Span: trace içindeki zamanlanmış operasyon.
- Structured log: parse edilebilir field'lar içeren log.
- Context propagation: diagnostic context'in boundary'ler boyunca taşınması.

## Araştırma Keywordleri

- `observability logs metrics traces correlation id`
- `tenant id structured logging`
- `opentelemetry context propagation`
- `trace log correlation`
- `multi tenant observability strategy`

## Sonuç

Production debugging için sinyallerin bağlanması gerekir. Tutarlı `correlation-id` ve doğrulanmış `tenant-id` politikası dağınık log, metric ve trace verisini anlaşılır request hikayesine dönüştürür.
