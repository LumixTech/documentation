---
title: Elasticsearch Temelleri
description: Elasticsearch nedir, Lucene tabanı, index/shard/replica/mapping, inverted index, OpenSearch ile karşılaştırma ve Lumix'te kullanım kararı.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Elasticsearch'ü **hiç görmemiş bir geliştirici**ye sıfırdan inşa eder. Inverted index'in PostgreSQL'in B-tree index'inden farkını, **index/shard/replica/mapping** kavramlarını, full-text search'ün altındaki mantığı, **OpenSearch fork**'unun hikayesini ve Lumix'in **neden Elasticsearch** seçtiğini anlatır. Detaylı indexing pattern ve Spring entegrasyonu sonraki sayfalarda.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Bir **kütüphane düşün**. PostgreSQL'in B-tree index'i, kitapları **yazara göre sıralanmış** bir kart kataloğudur — "Yaşar Kemal" diye arayabilirsin, ama kitabın **içeriğinde** "İnce Memed yağmurda yürürken" cümlesini bulmak istiyorsan kart kataloğu işe yaramaz; tek tek kitapları açıp okumak zorundasın.

Elasticsearch'ün **inverted index**'i ise bambaşka bir indeks: kütüphanedeki **her kelimenin**, hangi sayfada hangi kitapta geçtiğini gösteren devasa bir indekstir. "yağmurda" kelimesini ara → 47 kitap + sayfa numaraları çıkar. **Full-text search**'ün temeli budur.

### 1.2. Elasticsearch nedir?

Elasticsearch (ES), Java ile yazılmış, **Apache Lucene** üzerine kurulu, **dağıtık** bir search engine ve analytics platformudur. JSON document'leri index'ler, REST API üzerinden sorgulanır. 2010'da Shay Banon tarafından geliştirildi, ardından Elastic NV şirketi ile commercial bir ekosistem büyüdü.

**Ana yetenekleri:**

- Full-text search (relevance scoring ile)
- Yapısal sorgu (filter, range, term)
- Aggregation (sum, avg, histogram, terms, nested)
- Geo-spatial query
- Fuzzy / partial matching, "did you mean"
- Highlighting, suggester, autocomplete
- Distributed (shard/replica) ile petabyte ölçekte

### 1.3. Lucene nedir?

Apache Lucene, **Java ile yazılmış low-level full-text search library**'sidir. 1999'dan beri var. Inverted index, scoring (BM25, TF-IDF), tokenization, analyzer, stemming ona ait. Elasticsearch ve Solr **aynı Lucene'i** kullanır; üstüne dağıtık katman + REST API + cluster management koyarlar.

### 1.4. Document modeli

Elasticsearch **document-oriented**'tır. Bir document JSON'dır:

```json
{
  "_id": "01HXY...",
  "tenant_id": "abc-123",
  "type": "message",
  "subject": "Yarınki sınav hakkında",
  "body": "Merhaba veliler, yarın matematik sınavı...",
  "sender_name": "Hüseyin Öğretmen",
  "sent_at": "2026-05-27T10:30:00Z",
  "class_id": "11-A",
  "tags": ["sinav", "matematik"]
}
```

Document'ler **index**'e konur. Index ≈ ilişkisel DB'deki tablo (kabaca).

## 2. Hangi problemi çözüyor?

### 2.1. PostgreSQL full-text search yetmiyor mu?

PostgreSQL'in `tsvector` + GIN index ile temel full-text aramayı yapabilir. Küçük ölçekte yeterli. Ama:

- **Türkçe stemming** zayıf (eklerin nasıl ayrıştırılacağı limitli)
- Relevance scoring basit (BM25 yok, ts_rank var ama ileri ihtiyaç için yetmez)
- Aggregation/faceting yetersiz (her sorgu DB'yi yorar)
- Fuzzy search, "did you mean" yok
- Multi-field weighted query zor
- Highlight, suggester yok
- Index update DB transaction'ı yavaşlatır

### 2.2. SaaS senaryosunda ne tür sorgular var?

- "İnce Memed" mesajlarını bul, tenant scope'ta
- "Hüseyin öğretmenin son 30 günde gönderdiği mesajları" listele, tarih + tenant filter
- "Yoklama notu içeren tüm dokümanlarda" arama
- Autocomplete: "müf..." yazınca "müfredat", "müsait" öner
- Aggregation: "Bu ay hangi tag'ler en çok kullanıldı?"

PostgreSQL bunları **yapabilir** ama ölçek + relevance + latency kombinasyonu Elasticsearch'ün doğal alanı.

### 2.3. Source of truth + read projection ayrımı

Elasticsearch **operational source of truth değildir**:
- ACID transaction yok
- Multi-document atomic operation yok
- PostgreSQL'in ilişkisel garantileri yok

Lumix kararı: **PostgreSQL source of truth, Elasticsearch read projection**. Her ikisinde de veri var, ES sadece search için. Detay sonraki sayfada.

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Inverted index neye benzer?

`subject` alanını 3 document için index'leyelim:

```text
doc 1: "Yarınki sınav hakkında"
doc 2: "Sınav sonuçları"
doc 3: "Yarın okul tatil"
```

Tokenize edilir (analyzer ile):

```text
doc 1: [yarinki, sinav, hakkinda]
doc 2: [sinav, sonuclari]
doc 3: [yarin, okul, tatil]
```

Inverted index:

```text
hakkinda  → [1]
okul      → [3]
sinav     → [1, 2]
sonuclari → [2]
tatil     → [3]
yarin     → [3]
yarinki   → [1]
```

"sinav" sorgusu **O(log n)** zamanında doc 1 ve 2'yi bulur. PostgreSQL'in LIKE '%sinav%' sorgusu **O(n)** scan.

### 3.2. Index, shard, replica

| Kavram | Açıklama |
|---|---|
| **Index** | Document'lerin mantıksal koleksiyonu (örn. `lumix_messages`) |
| **Shard** | Index'in fiziksel parçası; Lucene index instance. Index oluşturulurken primary shard sayısı sabitlenir |
| **Replica** | Shard'ın kopyası; HA + read scaling |
| **Node** | ES instance (JVM process) |
| **Cluster** | Birden fazla node'un bir araya gelmesi |

```text
Cluster: lumix-search-prod
├── Node A
│   ├── lumix_messages shard 0 (primary)
│   ├── lumix_messages shard 1 (replica)
│   └── lumix_files shard 0 (primary)
├── Node B
│   ├── lumix_messages shard 0 (replica)
│   ├── lumix_messages shard 1 (primary)
│   └── lumix_files shard 0 (replica)
└── Node C
    └── ...
```

Bir node çökse, replica primary'ye terfi eder. Otomatik failover.

### 3.3. Mapping — dynamic vs explicit

**Mapping**, index'in schema'sıdır. Hangi field hangi type, hangi analyzer ile index'lenir.

**Dynamic mapping**: ES first document geldiğinde type'ı tahmin eder. Hızlı başlangıç ama production tehlikeli (string vs date karışıklığı, mapping explosion).

**Explicit mapping**: index oluştururken type'ları tanımlarsın. Lumix tercihi.

```json
PUT /lumix_messages
{
  "settings": {
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
  },
  "mappings": {
    "properties": {
      "tenant_id":   { "type": "keyword" },
      "type":        { "type": "keyword" },
      "subject":     { "type": "text", "analyzer": "lumix_tr" },
      "body":        { "type": "text", "analyzer": "lumix_tr" },
      "sender_name": { "type": "text", "fields": { "keyword": { "type": "keyword" } } },
      "sent_at":     { "type": "date" },
      "class_id":    { "type": "keyword" },
      "tags":        { "type": "keyword" }
    }
  }
}
```

`text` field: full-text search içindir, analyzer'dan geçer. `keyword` field: exact match içindir, aggregation/sort yapılır.

### 3.4. Query DSL

ES sorguları JSON. Örnek:

```json
GET /lumix_messages/_search
{
  "query": {
    "bool": {
      "filter": [
        { "term":  { "tenant_id": "abc-123" } },
        { "range": { "sent_at": { "gte": "now-30d/d" } } }
      ],
      "must": [
        { "match": { "body": "matematik sınav" } }
      ]
    }
  },
  "highlight": { "fields": { "body": {} } },
  "size": 20,
  "from": 0
}
```

`filter` clause: relevance score etkilemez (binary match), cache'lenebilir. `must` clause: relevance score etkiler. Lumix kuralı: `tenant_id` her zaman filter'da.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Kullanım alanları

| Kullanım | Index |
|---|---|
| Mesaj arama (communication-service) | `lumix_messages` |
| Dosya arama (file-service metadata) | `lumix_files` |
| Öğrenci arama (organization-service) | `lumix_students` |
| Audit log analizi (audit-service) | `lumix_audit` |
| Log aggregation (Loki primary, ES secondary fallback) | — |

### 4.2. Index naming convention

```text
lumix_{domain}_{installation_id}
```

Örnek: `lumix_messages_omer_okullari`. Her installation kendi ES cluster'ında — bu naming convention multi-cluster manager için yardımcı.

### 4.3. Tenant scoping

**KRİTİK KURAL**: her sorguda `tenant_id` term filter zorunlu. Bunu adapter katmanında otomatik enforce ederiz (sonraki sayfada detay).

### 4.4. Versioning ve compatibility

Index naming ileride ekleyebileceğimiz versioning ile:

```text
lumix_messages_v1     (aktif)
lumix_messages_v2     (yeni mapping)
lumix_messages        (alias → v1 veya v2)
```

Alias ile zero-downtime re-index (sonraki sayfada).

### 4.5. Donanım kararı

| Müşteri | Node count | Heap (her node) | Disk |
|---|---|---|---|
| Küçük | 1 node (replica yok, dev-friendly) | 2 GB | 50 GB |
| Orta | 3 node (replica 1) | 4 GB | 200 GB |
| Büyük | 5+ node + dedicated master | 8 GB | 500 GB+ |

JVM heap **31 GB üstüne çıkmaz** (compressed oop için). Disk SSD/NVMe zorunlu.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

| Alternatif | Neden elendi |
|---|---|
| **PostgreSQL tsvector** | Küçük ölçekte yeterli, ileride limit'leri var, ayrı engine getirmek doğru |
| **Apache Solr** | Olgun ama community ES'in arkasında, REST API daha az dostane |
| **MeiliSearch** | Hafif, hızlı ama feature set sınırlı (aggregation yok, scale limited) |
| **Typesense** | İlginç, ama feature parity ES'e göre düşük |
| **OpenSearch** | Ciddi alternatif (AWS fork). Lumix kararı: aşağıda |

### 5.2. Elasticsearch vs OpenSearch

**Tarih**: 2021'de Elastic, ES lisansını Apache 2.0'dan Elastic License + SSPL'e çevirdi (AWS gibi managed servisleri engellemek için). AWS bu karara karşılık ES'i fork etti: **OpenSearch** (Apache 2.0).

| Konu | Elasticsearch | OpenSearch |
|---|---|---|
| Lisans | Elastic License 2.0 + SSPL | Apache 2.0 |
| Self-host SaaS uyumu | Belirsiz (SSPL klauzlarını okuman gerek) | Tam uyumlu |
| API compatibility | — | ES 7.10 fork; sonra divergence başladı |
| Community momentum | Hâlâ büyük | Büyüyor (özellikle AWS müşterileri) |
| Yeni feature'lar | Elastic'ten önce gelir | Biraz gecikme |
| Lumix tercihi | **Elasticsearch** (free tier yeterli; SSPL distribute etmediğimiz için sorun değil) |

**Karar gerekçesi**: Lumix ES'i **dağıtmıyor**, müşterinin cluster'ında self-host ediyor. SSPL'in service-as-software klauzları bu modelde sorun çıkarmıyor. Olgunluk + feature ile ES.

**Adapter pattern**: Spring Data ES interface kullandığımız için ileride **OpenSearch adapter'a geçiş** mümkün, 7.10 fork olduğu için API kompatibilitesi yüksek.

### 5.3. Kabul ettiğimiz trade-off'lar

- **Eventual consistency**: ES projection, PostgreSQL'den biraz geç güncellenir (genelde &lt; 1 sn). UI'da "yeni gönderilen mesaj henüz arama sonuçlarında yok" durumu olabilir; explicit refresh path'i kullanırız.
- **Ekstra component**: bir engine daha demek operasyonel yük. Karşılığında PostgreSQL search yükünden kurtuluyor.
- **JVM heap memory**: ES bellek aç. Cluster planlanır.
- **Mapping change zor**: Existing field'ın type değişimi → re-index gerekir.

### 5.4. Ne değişirse kararı tekrar gözden geçiririz?

- Elastic lisansı daha kısıtlayıcı olursa **OpenSearch'e geçeriz**.
- Müşteri ortamı tamamen managed cloud isterse **AWS OpenSearch Service** veya **Elastic Cloud** kullanılabilir.

## 6. Pratik örnek

### 6.1. Docker Compose ile dev environment

```yaml
version: '3.8'
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.15.0
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
      - ES_JAVA_OPTS=-Xms2g -Xmx2g
    ports:
      - "9200:9200"
    volumes:
      - es-data:/usr/share/elasticsearch/data

volumes:
  es-data:
```

### 6.2. Kubernetes (ECK operator)

```yaml
apiVersion: elasticsearch.k8s.elastic.co/v1
kind: Elasticsearch
metadata:
  name: lumix-search
  namespace: search
spec:
  version: 8.15.0
  nodeSets:
  - name: data
    count: 3
    config:
      node.roles: ["master", "data", "ingest"]
    podTemplate:
      spec:
        containers:
        - name: elasticsearch
          env:
          - name: ES_JAVA_OPTS
            value: "-Xms4g -Xmx4g"
          resources:
            requests:
              memory: 8Gi
              cpu: 1000m
            limits:
              memory: 8Gi
              cpu: 2000m
    volumeClaimTemplates:
    - metadata:
        name: elasticsearch-data
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 200Gi
        storageClassName: fast-ssd
```

### 6.3. Spring Boot pom.xml

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>
```

### 6.4. application.yml

```yaml
spring:
  elasticsearch:
    uris: "https://elasticsearch.search.svc.cluster.local:9200"
    username: "lumix_app"
    password: "${ES_PASSWORD}"
    connection-timeout: 5s
    socket-timeout: 30s
```

### 6.5. İlk index oluşturma (curl)

```bash
curl -X PUT "elasticsearch:9200/lumix_messages" \
  -H 'Content-Type: application/json' \
  -d @- <<'EOF'
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties": {
      "tenant_id": { "type": "keyword" },
      "subject":   { "type": "text" },
      "body":      { "type": "text" },
      "sent_at":   { "type": "date" }
    }
  }
}
EOF
```

### 6.6. İlk arama

```bash
curl -X POST "elasticsearch:9200/lumix_messages/_search" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "bool": {
        "filter": [{ "term": { "tenant_id": "abc-123" } }],
        "must":   [{ "match": { "body": "matematik" } }]
      }
    }
  }'
```

## 7. Dikkat edilecek tuzaklar

- **Dynamic mapping production'da kapalı olmalı**. `dynamic: strict` veya `dynamic: false` ayarla; aksi halde tek bir hatalı document tüm mapping'i değiştirebilir.
- **Heap %50 host RAM'i geçmesin**. Diğer %50 OS page cache için (Lucene memory-mapped file).
- **Heap 31 GB üstüne çıkma**. Compressed object pointer kaybolur.
- **Shard sayısını yanlış seçme**. Az shard = scale problem, çok shard = overhead. Pratik: shard başına 10-50 GB.
- **Replica 0 production'da kullanma**. HA yoksa tek node failure'da data loss.
- **`_search` URL'inde tenant_id unutma**. Cross-tenant data leak en büyük risk.
- **JSON injection**. User input'u doğrudan query body'sine eklerken sanitize et.
- **Bulk index unutma**. Tek tek index 100x yavaş. Batch ile gönder.
- **Refresh interval default 1s**. Heavy write workload'da yükseltilebilir (-1 ile manual refresh).
- **ES'i source of truth yapma**. Backup PostgreSQL'den; ES re-index edilebilir.
- **Mapping field explosion**. Dinamik field eklenmesin (`mapping.total_fields.limit` default 1000 — aşılırsa cluster patlar).
- **Türkçe analyzer atlama**. `lumix_tr` custom analyzer kullanmadan "öğretmen" / "ogretmenler" eşleşmez.

## 8. Diğer konularla ilişkisi

- [Indexing Stratejisi](./02-indexing-strategy.md) — event-driven indexing, re-index
- [Spring Data Elasticsearch](./03-spring-data-elasticsearch.md) — Java implementasyon
- [PostgreSQL](../database-architecture) — source of truth karşılaştırma
- [Kafka](../event-driven-architecture) — projection için event consumer
- [Genel Mimari](../00-overview/03-overall-architecture.md) — ES sistem haritasında nerede

## 9. Daha derine inmek için

- Elastic — [Official Documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/index.html)
- Elastic — [Mapping reference](https://www.elastic.co/guide/en/elasticsearch/reference/current/mapping.html)
- OpenSearch — [Documentation](https://opensearch.org/docs/latest/)
- Apache Lucene — [Project Page](https://lucene.apache.org/)
- Araştırma keyword'leri: `elasticsearch vs opensearch license`, `inverted index lucene tutorial`, `elasticsearch shard replica design`, `elasticsearch turkish analyzer`

## 10. Sözlük

- **Elasticsearch (ES)** — Lucene tabanlı distributed search ve analytics engine.
- **Lucene** — Apache'in Java full-text search library'si; ES'in temeli.
- **Document** — ES'te tek bir JSON kayıt.
- **Index** — Document'lerin mantıksal koleksiyonu (≈ tablo).
- **Shard** — Index'in fiziksel parçası; Lucene instance.
- **Replica** — Shard'ın kopyası; HA + read scaling için.
- **Mapping** — Index schema'sı (field tipleri ve analyzer'ları).
- **Analyzer** — Text'i token'lara çeviren pipeline (tokenizer + filter).
- **Inverted index** — Kelime → document listesi haritası; full-text search'ün temeli.
- **OpenSearch** — AWS tarafından fork edilen Apache 2.0 lisanslı ES alternatifi.
- **BM25** — Modern relevance scoring algoritması; ES default.
- **Source of truth** — Verinin authoritative kaynağı; Lumix'te PostgreSQL.
- **Projection** — Source of truth'tan türetilen, read-optimized kopya; Lumix'te ES.
