---
title: "OpenTelemetry ve Tracing Stratejisi"
description: Kritik use case'ler için span tasarımı, context propagation ve Spring Boot odaklı tracing stratejisi.
sidebar_position: 2
---

## Giriş

Tracing, bir request veya workflow'un sistem içinde nasıl ilerlediğini açıklar. Akış HTTP API, domain service, repository, message broker, scheduled job ve external integration sınırlarını geçtiğinde özellikle değerlidir.

OpenTelemetry; trace, metric, log, context propagation, instrumentation ve exporter için vendor-neutral bir model sunar.

Bu doküman üç kritik ürün use case'i için span haritası düşüncesini tanımlar.

## Neden Önemli

Log bize bir şeyin fail olduğunu söyler. Metric latency'nin arttığını söyler. Trace operasyonlar arasındaki path, timing ve causality bilgisini gösterir.

Tracing yoksa:

- async workflow orijinal request'e bağlanamaz,
- yavaş DB query geniş API latency içinde saklanır,
- external service gecikmesi application slowness gibi görünür,
- cross-module failure için manuel log kazısı gerekir.

## Temel Kavramlar

- OpenTelemetry: telemetry toplama ve export etmek için standart observability framework'ü.
- Trace: tek request veya workflow'un tam execution path'i.
- Span: trace içindeki tek zamanlanmış operasyon.
- Parent/child span: call hierarchy ilişkisi.
- Attribute: span'e eklenen structured metadata.
- Context propagation: trace context'in boundary'ler boyunca taşınması.
- Sampling: hangi trace'lerin saklanacağı kararı.
- Instrumentation: telemetry üreten otomatik veya manuel kod.

## Gerçek Ürün Geliştirmede Problem

Auto-instrumentation faydalıdır ama ürün niyetini bilmez. HTTP ve DB span'leri gösterebilir; fakat şu business milestone'ları kaçırabilir:

- attendance marked,
- chat message persisted,
- payment authorization requested,
- notification event published,
- permission check denied.

Trace hem teknik operasyonları hem domain açısından anlamlı adımları göstermelidir.

## Yaklaşım / Tasarım

### Span İsimlendirme Kuralları

Operasyonu anlatan stabil isimler kullan:

```text
HTTP POST /attendance/mark
AttendanceService.markAttendance
AttendanceRepository.saveBatch
NotificationPublisher.publishAttendanceChanged
```

High-cardinality isimlerden kaçın:

```text
kötü: GET /students/8f4e6...
iyi: GET /students/{studentId}
```

### Zorunlu Span Attribute'ları

- `correlation-id`
- `tenant-id`
- güvenli ve izinli ise `user-id`
- `module`
- `use_case`
- `outcome`
- hata varsa `error.code`
- DB span için `db.operation`
- broker span için `messaging.destination`

### Kritik Use Case 1: Attendance Peak

```text
Trace: mark_attendance
  Span: HTTP POST /attendance/mark
    Span: AuthContext.resolve
    Span: Authorization.check role_permission/user_permission
    Span: AttendanceService.validateWindow
    Span: AttendanceRepository.saveBatch
    Span: OutboxRepository.insert attendance_marked
    Span: Transaction.commit
    Span: MessageBroker.publish attendance_marked
```

Önemli attribute'lar:

- `tenant-id`
- `classroom_id`
- `student_count`
- `attendance_window`
- `outcome`

### Kritik Use Case 2: Chat Message Flow

```text
Trace: send_chat_message
  Span: WebSocket inbound /chat/send
    Span: ChatAuthorization.checkParticipant
    Span: ChatMessageService.persist
    Span: AttachmentService.validateReferences
    Span: OutboxRepository.insert message_created
    Span: WebSocket fanout
    Span: PushNotification.enqueue
```

Önemli attribute'lar:

- `tenant-id`
- `conversation_id`
- `message_type`
- `has_attachment`
- `delivery_channel`

### Kritik Use Case 3: Payment Flow

```text
Trace: payment_attempt
  Span: HTTP POST /payments
    Span: PaymentAuthorization.check
    Span: PaymentService.createAttempt
    Span: PaymentProvider.authorize
    Span: PaymentRepository.updateStatus
    Span: AuditLog.record payment_attempt
    Span: NotificationPublisher.publishPaymentUpdated
```

Önemli attribute'lar:

- `tenant-id`
- `payment_id`
- `provider`
- `provider_status`
- `outcome`

## Sektör Standardı / Best Practice

- Framework-level span'ler için auto-instrumentation kullan.
- Business-critical operasyonlara manuel span ekle.
- Span isimlerini stabil ve low-cardinality tut.
- HTTP header, queue ve scheduled job boyunca trace context taşı.
- Logları trace ID ve `correlation-id` ile bağla.
- High-volume endpoint'lerde sampling'i dikkatli kullan; error trace'leri sakla.
- Span attribute içine secret veya kişisel veri koyma.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
trace_business_operation(use_case, context):
  span = tracer.start_span(use_case)
  span.set_attribute('tenant-id', context.tenant_id)
  span.set_attribute('correlation-id', context.correlation_id)
  span.set_attribute('module', context.module)

  try:
    result = execute_use_case()
    span.set_attribute('outcome', 'success')
    return result
  catch error:
    span.record_exception(error)
    span.set_attribute('outcome', 'error')
    raise error
  finally:
    span.end()
```

## Bizim Notlarımız / Takım Kararları

- Trace'ler async workflow ve module-to-module flow'ları kapsamalıdır.
- İlk üç span map: attendance peak, chat message flow ve payment flow.
- Ürün anlamı auto-instrumentation ile görünmüyorsa manuel span eklenmelidir.
- Trace context kuralları [Observability: Log, Metric, Trace ve Correlation ID](./observability-logs-metrics-traces-correlation-id) ile uyumlu olmalıdır.

## Harici Referanslar

- OpenTelemetry: [Documentation](https://opentelemetry.io/docs/)

## Sözlük

- Trace: workflow'un uçtan uca path'i.
- Span: trace içindeki zamanlanmış operasyon.
- Attribute: telemetry üzerine eklenen structured metadata.
- Sampling: trace'lerin bir kısmını saklama stratejisi.
- Instrumentation: telemetry üreten kod veya agent.
- Context propagation: trace context'in boundary'ler boyunca taşınması.

## Araştırma Keywordleri

- `opentelemetry tracing spans context propagation`
- `spring boot tracing strategy`
- `trace log correlation opentelemetry`
- `business spans distributed tracing`
- `async trace propagation message broker`

## Sonuç

Tracing hem teknik latency'yi hem business causality'yi göstermelidir. OpenTelemetry standart modeli sağlar; anlamlı ürün span'lerini tanımlamak ise takımın mimari sorumluluğudur.
