---
title: Event-Driven Indexing Stratejisi
description: Kafka consumer → ES bulk insert pattern, PostgreSQL source-of-truth, re-index akışı, schema evolution, alias swap ile zero-downtime.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Lumix'in Elasticsearch'ü **source of truth değil read projection** olarak kullanmasının ardındaki pattern'i, **Kafka event-driven indexing** akışını, **bulk insert** optimizasyonunu, **re-index** stratejisini (Kafka log replay + alias swap), **schema evolution** yöntemini ve **idempotency** kurallarını anlatır. PostgreSQL'deki authoritative veriden ES'e tutarlı, dayanıklı projection nasıl beslenir burada.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Bir **gazete arşivi** düşün. Asıl gazeteler **kasada** (PostgreSQL). Ama hızlı arama için bir **fiş kataloğu** tutulur (Elasticsearch). Yeni gazete geldiğinde **bir kişi** (event consumer) onu okur ve kataloğa fiş düşer. Fiş katalog **yanlış olabilir** veya **gecikebilir**, ama her zaman asıl kasaya bakarak **yeniden inşa edilebilir**. Kasayı kaybedersek felaket; katalogu kaybedersek sadece yeniden oluştururuz.

### 1.2. CQRS ve read projection

Lumix tam CQRS uygulamaz ama prensipler:
- **Command side**: PostgreSQL'de write. ACID, transaction'lar, ilişkisel kurallar.
- **Query side**: Elasticsearch'te read-optimized projection. Eventual consistency.

Tek bir entity (örn. mesaj) iki yerde tutulur:
- PostgreSQL `messages` tablosu — authoritative
- Elasticsearch `lumix_messages` index'i — search projection

İki kopyayı senkronize tutan mekanizma **Kafka event stream**.

### 1.3. Neden ayrım?

- **Read vs write workload farklı**: search query'leri PostgreSQL'e vurursa transactional yükü etkiler.
- **Şema esnekliği**: ES'te aggregate edilmiş, denormalized projection mantıklı; PostgreSQL'de normalize tutarız.
- **Scale farklı**: read 10x write olabilir; ES read replica scale'i daha ucuz.

## 2. Hangi problemi çözüyor?

### 2.1. Dual-write problemi

Naif yaklaşım: aynı kod hem PostgreSQL'e hem ES'e yazar.

```java
// ANTI-PATTERN
@Transactional
public void sendMessage(Message msg) {
    messageRepository.save(msg);          // PostgreSQL
    elasticsearchClient.index(msg);       // ES
}
```

Sorun: ES bağlantısı koparsa? PostgreSQL transaction commit oldu ama ES'e ulaşamadık. **Tutarsızlık**. Tersine, ES yazıldı ama PostgreSQL rollback olduysa? **Phantom data**.

İki ayrı sistem üzerinde **atomic dual-write yapılamaz**.

### 2.2. Çözüm: outbox + event consumer

- PostgreSQL transaction'da hem `messages` tablosuna hem `outbox_events` tablosuna yaz (atomic).
- Outbox relay (Debezium veya custom poller) outbox → Kafka publish.
- Indexer service Kafka consume → ES bulk insert.

Eventual consistency: ES birkaç saniye geç olabilir, ama **eninde sonunda tutarlı**.

### 2.3. Re-index problemi

Mapping değişimi, yeni feature, full re-index gerektiren senaryolarda:
- "ES'i sıfırdan inşa et" demek mümkün olmalı
- Production trafiği bozulmadan yeni index'e geçiş yapılmalı
- Kafka log replay ile veya PostgreSQL → ES batch dump ile yapılır

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Yüksek seviye akış

```text
┌──────────────────┐
│  Domain Service  │ (örn. communication-service)
└────────┬─────────┘
         │ INSERT message
         │ INSERT outbox_event
         ▼  (transaction commit)
┌──────────────────┐
│   PostgreSQL     │ ← source of truth
└────────┬─────────┘
         │ Outbox relay (Debezium / poller)
         ▼
┌──────────────────┐
│   Kafka topic    │ communication.message.sent.v1
└────────┬─────────┘
         │
         ▼
┌──────────────────────┐
│  Indexer Consumer    │ (ayrı service veya domain service içi consumer)
│  - batch up to 500   │
│  - bulk index to ES  │
└────────┬─────────────┘
         │ ES bulk API
         ▼
┌──────────────────┐
│  Elasticsearch   │ ← search projection
└──────────────────┘
```

### 3.2. Indexer sorumlulukları

- Kafka consumer olarak event'leri al
- **Idempotent index**: aynı event tekrar gelirse aynı document ID ile re-write yap (versioning ile)
- **Bulk batch**: 100-500 event toplayıp tek `_bulk` çağrısı
- **DLQ handling**: 3 retry sonrası DLQ topic'ine yaz
- **Error visibility**: failure rate metrik + alert

### 3.3. Document modeli

PostgreSQL row direkt ES'e gitmez — **flattened, denormalized** projection oluşur. Örnek mesaj:

PostgreSQL'de:
```text
messages: id, conversation_id, sender_id, body, sent_at
conversations: id, tenant_id, type, name
users: id, name, email
```

ES'te:
```json
{
  "_id": "01HXY...",
  "tenant_id": "abc-123",          // conversation'dan
  "conversation_id": "...",
  "conversation_name": "11-A Veli Grubu",
  "sender_id": "...",
  "sender_name": "Hüseyin Öztürk", // user'dan
  "body": "Yarınki sınav...",
  "sent_at": "2026-05-27T10:30:00Z"
}
```

Indexer event'i gördüğünde gerekiyorsa **conversation ve sender bilgisini gRPC ile resolve eder** (cache'li), denormalized document'i ES'e yazar.

### 3.4. Bulk operation

```json
POST /_bulk
{ "index": { "_index": "lumix_messages", "_id": "01HXY..." } }
{ "tenant_id": "abc-123", "body": "...", ... }
{ "index": { "_index": "lumix_messages", "_id": "01HXZ..." } }
{ "tenant_id": "abc-123", "body": "...", ... }
```

Tek HTTP call ile 500 document. Throughput 100x artar.

### 3.5. Re-index ve alias swap

```text
1. lumix_messages_v1 (production, alias: lumix_messages → v1)
2. Yeni mapping ile lumix_messages_v2 oluştur
3. Kafka offset'i geçmişe sar (örn. son 30 gün) veya PostgreSQL → ES tek seferlik batch dump
4. v2'ye yazımı paralel olarak başlat (consumer group 2)
5. v2 hazır ve up-to-date olduğunda alias swap: lumix_messages → v2
6. v1'i sil (veya snapshot al)
```

Bu pattern **expand and contract** prensibinin ES versiyonu. Production okumayı bozmaz.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Indexer service yapısı

Lumix iki seçenek arasında karar verdi:

**Seçenek A**: Ayrı bir `search-indexer-service` (tüm domain event'leri consume eden tek servis).

**Seçenek B**: Her domain service kendi indexer consumer'ını barındırır.

**Lumix kararı: B**. Her domain (communication, file, organization, audit) kendi index'ini ve consumer'ını yönetir. Avantajı: domain ekibi tam kontrol, decoupled deployment. Dezavantajı: ES connection pool her servis tarafında.

### 4.2. Topic ve index eşleşmesi

| Kafka topic | Index |
|---|---|
| `communication.message.sent.v1` | `lumix_messages` |
| `communication.announcement.published.v1` | `lumix_announcements` |
| `file.upload.completed.v1` | `lumix_files` |
| `organization.student.registered.v1` | `lumix_students` |
| `audit.event.recorded.v1` | `lumix_audit` |

### 4.3. Idempotency

Kafka at-least-once delivery yapar. Aynı event tekrar gelirse:
- ES document ID = event'in payload'undaki entity ID (örn. `message.id`)
- `_bulk` `index` action (upsert) kullanılır
- Out-of-order event protection: `_version` veya `_version_type=external` ile event timestamp'i version olarak gönder

```json
POST /_bulk
{ "index": {
    "_index": "lumix_messages",
    "_id": "01HXY...",
    "version": 1716800400000,
    "version_type": "external"
}}
{ ... payload ... }
```

ES gelen version daha eskiyse reject eder. Stale event sorunsuz.

### 4.4. Re-index trigger'ları

| Senaryo | Yöntem |
|---|---|
| Mapping değişimi | v2 index + alias swap + Kafka 30 günlük replay |
| Tam ES disaster recovery | PostgreSQL'den batch dump + sonra Kafka catch-up |
| Yeni denormalized field | Domain service event'e ekler, indexer önce sadece yeni event'lerde populate eder; eski document'leri scheduled job ile günceller |
| Schema major version | Yeni index naming (`v3`) + manual cutover plan |

### 4.5. Backfill (PostgreSQL → ES)

Disaster recovery veya yeni index:

```text
1. Application: read-only mode (opsiyonel) veya freeze write
2. Batch script:
   SELECT * FROM messages WHERE tenant_id = ?
   ORDER BY id
   LIMIT 1000 OFFSET ?
3. Her batch → ES _bulk insert (idempotent ID'lerle)
4. Bittiğinde Kafka offset'i şu andan başlatarak resume
```

### 4.6. Monitoring

| Metrik | Anlam |
|---|---|
| `indexer_lag_seconds` | Event publish ile ES index arasındaki gecikme |
| `indexer_failure_rate` | % failed bulk operations |
| `indexer_batch_size` | Average batch size (target: 100-500) |
| `es_indexing_rate` | Documents per second |
| `es_search_latency_p95` | Query response time |

Alarm: lag > 30 sn = critical (UI'da kullanıcı kayıp veri görür).

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

| Alternatif | Neden elendi |
|---|---|
| **Dual-write (app PostgreSQL + ES synchronously)** | Atomic değil, tutarsızlık riski |
| **Logical replication (PostgreSQL → ES connector)** | Debezium ile mümkün ama denormalization elle yapmak zor; mapping eventually gets ugly |
| **Trigger-based (PostgreSQL trigger → notify)** | Tightly coupled, scalability zayıf |
| **Synchronous indexing during request** | Latency artar, ES downtime business request'i bloklar |

### 5.2. Trade-off

- **Eventual consistency**: tipik 200ms-2sn, peak'te 30sn'e çıkabilir. UI'da "yeni mesajınız listede görünmüyor" durumu kabul.
- **Operasyonel kompleksite**: outbox + Kafka + indexer + ES. Hexagonal architecture katmanlama gerektirir.
- **Re-index disipline**: schema değişimi planlanmalı; ad-hoc yapılamaz.

### 5.3. Ne değişirse kararı tekrar gözden geçiririz?

- ES bağımlılığı kritik hale gelirse (örn. millisecond-level consistency gerekir), search PostgreSQL'e geri taşınır (tsvector ile küçük scope).
- Çok büyük replication lag persistent olursa partitioning + paralel consumer skalı yükseltilir.

## 6. Pratik örnek

### 6.1. Spring Data ES document

```java
package com.lumix.communication.search;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.List;

@Document(indexName = "lumix_messages")
public record MessageDocument(
        @Id String id,
        @Field(type = FieldType.Keyword) String tenantId,
        @Field(type = FieldType.Keyword) String conversationId,
        @Field(type = FieldType.Text, analyzer = "lumix_tr") String conversationName,
        @Field(type = FieldType.Keyword) String senderId,
        @Field(type = FieldType.Text, analyzer = "lumix_tr") String senderName,
        @Field(type = FieldType.Text, analyzer = "lumix_tr") String body,
        @Field(type = FieldType.Keyword) List<String> attachmentIds,
        @Field(type = FieldType.Date) Instant sentAt,
        @Version Long version
) {}
```

### 6.2. Kafka consumer ile bulk indexer

```java
package com.lumix.communication.search;

import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MessageIndexer {

    private final ElasticsearchTemplate esTemplate;
    private final ConversationLookup conversationLookup;
    private final UserLookup userLookup;

    @KafkaListener(
            topics = "communication.message.sent.v1",
            groupId = "communication-indexer",
            batch = "true",
            containerFactory = "batchProtoKafkaListenerContainerFactory"
    )
    public void onBatch(List<MessageSentEvent> events) {
        List<IndexQuery> queries = new ArrayList<>(events.size());
        for (MessageSentEvent ev : events) {
            ConversationView conv = conversationLookup.find(ev.getConversationId());
            UserView sender = userLookup.find(ev.getSenderId());

            MessageDocument doc = new MessageDocument(
                    ev.getMessageId(),
                    conv.tenantId().toString(),
                    conv.id().toString(),
                    conv.name(),
                    sender.id().toString(),
                    sender.displayName(),
                    ev.getBody(),
                    ev.getAttachmentIdsList(),
                    Instant.ofEpochMilli(ev.getSentAtEpochMillis()),
                    ev.getSentAtEpochMillis()
            );

            queries.add(new IndexQueryBuilder()
                    .withId(doc.id())
                    .withObject(doc)
                    .withVersion(doc.version())
                    .withVersionType(org.elasticsearch.index.VersionType.EXTERNAL)
                    .build());
        }
        esTemplate.bulkIndex(queries, MessageDocument.class);
    }
}
```

### 6.3. Index bootstrap (idempotent)

```java
@Component
public class MessageIndexInitializer {

    private final ElasticsearchTemplate esTemplate;

    @PostConstruct
    public void initialize() {
        IndexOperations indexOps = esTemplate.indexOps(MessageDocument.class);
        if (!indexOps.exists()) {
            indexOps.create(loadSettings(), loadMapping());
        }
    }

    private Document loadSettings() {
        return Document.parse("""
                {
                  "number_of_shards": 3,
                  "number_of_replicas": 1,
                  "analysis": {
                    "analyzer": {
                      "lumix_tr": {
                        "type": "custom",
                        "tokenizer": "standard",
                        "filter": ["lowercase", "asciifolding", "turkish_stem"]
                      }
                    },
                    "filter": {
                      "turkish_stem": { "type": "stemmer", "language": "turkish" }
                    }
                  }
                }
                """);
    }

    private Document loadMapping() {
        return Document.parse(/* mapping JSON */);
    }
}
```

### 6.4. Re-index workflow (Temporal pseudo)

```java
@WorkflowInterface
public interface ReindexWorkflow {
    @WorkflowMethod
    void reindex(String fromIndex, String toIndex, String aliasName);
}

@Component
public class ReindexWorkflowImpl implements ReindexWorkflow {
    private final ReindexActivities a = Workflow.newActivityStub(ReindexActivities.class);

    @Override
    public void reindex(String fromIndex, String toIndex, String aliasName) {
        a.createIndex(toIndex);
        a.reindexFromSource(fromIndex, toIndex);   // POST _reindex
        a.waitForRefresh(toIndex);
        a.swapAlias(aliasName, fromIndex, toIndex);
        a.deleteIndex(fromIndex);
    }
}
```

### 6.5. Alias swap atomic operation

```bash
curl -X POST "elasticsearch:9200/_aliases" -H 'Content-Type: application/json' -d '{
  "actions": [
    { "remove": { "index": "lumix_messages_v1", "alias": "lumix_messages" } },
    { "add":    { "index": "lumix_messages_v2", "alias": "lumix_messages" } }
  ]
}'
```

Application'ın yazımı/okuma alias üzerinden olduğu için **kesintisiz**.

## 7. Dikkat edilecek tuzaklar

- **Dual-write kullanma**. Outbox + Kafka şart.
- **Bulk batch'i çok küçük tutmak**. 1-10 doc'la `_bulk` çağırmak overhead. Hedef: 100-500.
- **Bulk batch'i çok büyük tutmak**. 10000+ doc = HTTP timeout, memory pressure.
- **Versioning olmadan idempotent yazma**. Out-of-order event eski state'i geri yazabilir.
- **Mapping change olmadan re-index**. Hiç değişiklik gerekmez; ileride bug.
- **`refresh=true` her index'te kullanma**. Performance ölür; default 1s yeter.
- **Indexer'ı app pod'una koymak**. Domain service pod'unu yorar; idealde ayrı pod (Lumix burada esnek: domain'in kendi indexer'ı, ama scale ayrı).
- **DLQ'yu izlemeyi unutma**. Failed event'ler birikir, kimse bakmaz.
- **gRPC lookup batch'lemeden**. Conversation/user lookup tek tek yapılırsa indexer yavaşlar; cache + batch lookup.
- **Alias yerine direkt index ismi kullanma**. Alias yoksa re-index sırasında kod değişikliği gerekir.
- **Re-index sırasında write'ı durdurma**. Hep iki index'e paralel yaz (dual-write to indexer için OK, source of truth'a değil).

## 8. Diğer konularla ilişkisi

- [Elasticsearch Temelleri](./01-elasticsearch-fundamentals.md) — alt yapı
- [Spring Data Elasticsearch](./03-spring-data-elasticsearch.md) — Java kullanım
- [Outbox Pattern](../02-architecture-patterns/06-outbox-pattern.md)
- [Kafka Topic Design](../event-driven-architecture)
- [Schema Evolution](../engineering-notes) — backward compatibility

## 9. Daha derine inmek için

- Elastic — [Bulk API](https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html)
- Elastic — [Reindex API](https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-reindex.html)
- Elastic — [Index aliases](https://www.elastic.co/guide/en/elasticsearch/reference/current/aliases.html)
- Elastic — [Versioning](https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-index_.html#_versioning)
- Araştırma keyword'leri: `elasticsearch event driven indexing pattern`, `cdc to elasticsearch`, `elasticsearch alias zero downtime reindex`, `bulk api batch size optimization`

## 10. Sözlük

- **Source of truth** — Authoritative veri kaynağı (Lumix'te PostgreSQL).
- **Projection** — Source of truth'tan türetilen okuma-optimize kopya.
- **Eventual consistency** — Zamanla tutarlılığa ulaşan yapı.
- **Dual-write** — Aynı kodun iki sisteme birden yazması (ANTI-PATTERN, Lumix kullanmaz).
- **Outbox pattern** — Transaction içinde event tablosuna yazıp ayrı relay ile publish etmek.
- **Bulk API** — ES'te tek HTTP call ile çok document index etme.
- **Alias** — Bir veya daha fazla index'i tek isimle gösteren ES feature'ı.
- **Re-index** — Bir index'ten başka bir index'e document'leri taşıma.
- **Idempotency** — Aynı operasyonu tekrar etmenin sonucu değiştirmemesi.
- **External versioning** — Application'ın kontrol ettiği version sayısı; ES eskiyse reject eder.
- **Backfill** — Eski verinin batch ile yeni index'e doldurulması.
- **CQRS** — Command Query Responsibility Segregation; write ve read modellerinin ayrılması.
