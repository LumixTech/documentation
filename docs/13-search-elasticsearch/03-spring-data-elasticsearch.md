---
title: Spring Data Elasticsearch ile Sorgu
description: Repository + native query, ElasticsearchOperations, custom query DSL, tenant-scoped sorgular, pagination ve aggregation.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Lumix'te Elasticsearch ile sorgu yazarken **Spring Data Elasticsearch** kütüphanesinin nasıl kullanıldığını, **repository pattern + native query** dengesini, **ElasticsearchOperations** API'sini, **tenant-scoped query enforcement**'ı, **pagination/aggregation** örneklerini ve **production tuzaklarını** anlatır. Önceki sayfa indexing'i kurguladı; bu sayfa **arama tarafını** yazıyor.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

JPA + Hibernate Java'da PostgreSQL'i nesne-yönelimli sorgulamayı sağlar. Aynı şekilde **Spring Data Elasticsearch**, ES'i Java-friendly bir interface ile kullanmamızı sağlar. `@Repository` arkasında ES query DSL'i otomatik çevrilir. Karmaşık sorgu için `ElasticsearchOperations` ile **native query** yazarız (raw JSON benzeri Java builder).

### 1.2. Üç katman

| Katman | Ne işe yarar | Ne zaman kullanılır |
|---|---|---|
| **ElasticsearchRepository** | Spring Data style CRUD + finder | Basit field-based query (`findByTenantId`) |
| **ElasticsearchTemplate / Operations** | Query DSL ile native sorgu | Bool query, aggregation, custom scoring |
| **REST client (direkt)** | Raw HTTP/JSON | Çok özel feature, debugging |

Lumix tercihi: 80% Operations, 20% Repository. Tenant scoping ve dynamic query ihtiyacı Repository'nin convention-based finder'ını aşar.

## 2. Hangi problemi çözüyor?

### 2.1. Naked ES client problemi

```java
// ANTI-PATTERN: low-level client
RestClient client = ...;
String json = """
    { "query": { "bool": { ... } } }
    """;
Response response = client.performRequest(...);
String body = EntityUtils.toString(response.getEntity());
// JSON parse, error handling, mapping ...
```

Her sorgu için JSON string oluşturmak, parse etmek, error handle etmek **manuel**. Tip güvenliği yok. Test edilemez.

### 2.2. Spring Data ile ne kazanırız?

- **Type-safe document mapping** (`@Document`, `@Field`)
- **Repository interface** ile otomatik implementation
- **Criteria/NativeQuery DSL** — Java builder pattern, type-safe
- **Pagination** built-in
- **Aggregation** wrapper
- **Test support** (`@DataElasticsearchTest`)

### 2.3. Tenant scoping enforcement

Lumix kritik kuralı: **her ES sorgusunda tenant_id filter zorunlu**. Manuel her query'de yazmak hatadır. Spring Data + custom executor ile **otomatik enforce** edilir.

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Document mapping

```java
@Document(indexName = "lumix_messages")
public record MessageDocument(
        @Id String id,
        @Field(type = FieldType.Keyword) String tenantId,
        @Field(type = FieldType.Text, analyzer = "lumix_tr") String body,
        @Field(type = FieldType.Date) Instant sentAt
) {}
```

`@Document` index ismini, `@Field` her field'ın tipini belirler. Application startup'ta ES'e mapping push edilebilir.

### 3.2. Repository

```java
public interface MessageSearchRepository
        extends ElasticsearchRepository<MessageDocument, String> {
    // Basit field-based finder (Lumix bunu çok kullanmaz — tenant scope eksik)
}
```

Repository pattern Lumix'te **sadece save / get by ID** için kullanılır. Search her zaman custom executor üzerinden.

### 3.3. ElasticsearchOperations + NativeQuery

```java
NativeQuery query = NativeQuery.builder()
        .withQuery(q -> q
                .bool(b -> b
                        .filter(f -> f.term(t -> t.field("tenant_id").value(tenantId)))
                        .must(m -> m.match(mq -> mq.field("body").query("matematik")))
                )
        )
        .withSort(s -> s.field(f -> f.field("sent_at").order(SortOrder.Desc)))
        .withPageable(PageRequest.of(0, 20))
        .build();

SearchHits<MessageDocument> hits = esOperations.search(query, MessageDocument.class);
```

`SearchHits` her hit için score, highlight, source döner.

### 3.4. Tenant scope filter — automatic enforcement

Lumix custom `TenantScopedSearchExecutor`:

```java
public class TenantScopedSearchExecutor {

    private final ElasticsearchOperations operations;
    private final TenantContext tenantContext;

    public <T> SearchHits<T> search(NativeQuery query, Class<T> documentType) {
        UUID tenantId = tenantContext.currentTenantId();
        NativeQuery scoped = injectTenantFilter(query, tenantId);
        return operations.search(scoped, documentType);
    }

    private <T> NativeQuery injectTenantFilter(NativeQuery query, UUID tenantId) {
        // Mevcut query'yi bool query içine sar, tenant_id term filter ekle
        ...
    }
}
```

Domain kodu **doğrudan operations.search()** çağırmaz; her zaman `TenantScopedSearchExecutor` üzerinden. Bu wrap **defense-in-depth** — application bug'ı olsa bile tenant leak engellenir.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Search use case kompozisyonu

```text
Controller → SearchUseCase → TenantScopedSearchExecutor → ElasticsearchOperations → ES
```

Domain spesifik SearchUseCase her servis tarafında:
- `MessageSearchUseCase` (communication-service)
- `FileSearchUseCase` (file-service)
- `StudentSearchUseCase` (organization-service)

### 4.2. Pagination

ES default **from + size** pagination. Pratik limit: from = 10000 (deep pagination yavaş). Daha derin için **search_after** kullanılır.

```java
NativeQuery query = NativeQuery.builder()
        .withQuery(...)
        .withSort(s -> s.field(f -> f.field("sent_at").order(SortOrder.Desc)))
        .withPageable(PageRequest.of(pageNumber, pageSize))
        .build();
```

Lumix kural: UI pagination 0-200 sayfa arası. Daha derinine inmek için **filter daraltma** veya `search_after` cursor.

### 4.3. Aggregation

"Bu ay en çok kullanılan tag'ler":

```java
NativeQuery aggQuery = NativeQuery.builder()
        .withQuery(q -> q.bool(b -> b
                .filter(f -> f.term(t -> t.field("tenant_id").value(tenantId)))
                .filter(f -> f.range(r -> r.field("sent_at")
                        .gte(JsonData.of("now-30d/d"))))))
        .withAggregation("top_tags",
                Aggregation.of(a -> a.terms(t -> t.field("tags").size(10))))
        .withMaxResults(0) // sadece aggregation, hit lazım değil
        .build();

SearchHits<MessageDocument> result = executor.search(aggQuery, MessageDocument.class);
ElasticsearchAggregations aggs = (ElasticsearchAggregations) result.getAggregations();
```

### 4.4. Highlight

```java
NativeQuery query = NativeQuery.builder()
        .withQuery(...)
        .withHighlightQuery(new HighlightQuery(
                new Highlight(List.of(
                        new HighlightField("body", HighlightFieldParameters.builder()
                                .withPreTags("<mark>")
                                .withPostTags("</mark>")
                                .withFragmentSize(150)
                                .build())
                )),
                MessageDocument.class))
        .build();
```

UI'da arama sonuçlarında matched keyword'ler `<mark>` ile sarılır.

### 4.5. Did you mean / suggester

```java
NativeQuery suggestQuery = NativeQuery.builder()
        .withSuggester(Suggester.of(s -> s
                .text("matemiatik")
                .suggesters("body-suggest", FieldSuggester.of(fs -> fs
                        .term(t -> t.field("body").size(3))))))
        .build();
```

"matemiatik" yazılırsa "matematik" önerilir.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

| Alternatif | Neden elendi |
|---|---|
| **Low-level REST client (Java)** | Boilerplate çok, type safety yok |
| **High-level REST client (deprecated 8.x sonrası)** | Elastic deprecated etti, yeni Java API Client önerildi |
| **Java API Client (raw)** | Builder iyi ama Spring entegrasyonu yok; transaction, repository, test support eksik |
| **Spring Data Elasticsearch (Lumix tercihi)** | Tam Spring ekosistemi entegrasyonu, builder DSL üzerinde abstraction |
| **JOOQ-style typed builder** | Mevcut değil |

### 5.2. Trade-off

- **Performance overhead**: Spring Data wrapping minimal, ama 1-2ms ekler. Aggregate query'lerde önemli değil.
- **API surface uyumsuzluğu**: Spring Data ES version güncellemesi ES upgrade ile uyumsuz olabilir. Lumix Spring Boot ile birlikte tutarlı versiyon.
- **Native API'ye düşme**: Çok özel feature için `JsonpMapper` ile raw JSON inject edilebilir.

### 5.3. Repository vs Operations

| Kullanım | Repository | Operations |
|---|---|---|
| Simple finder | ✓ |  |
| Save/get/delete | ✓ | ✓ |
| Complex bool query |  | ✓ |
| Tenant scope enforcement |  | ✓ (zorunlu) |
| Aggregation |  | ✓ |
| Highlight |  | ✓ |

Lumix gerçek hayatta **%80 Operations**, %20 Repository (sadece basic CRUD için).

## 6. Pratik örnek

### 6.1. application.yml

```yaml
spring:
  elasticsearch:
    uris: "https://elasticsearch.search.svc.cluster.local:9200"
    username: "lumix_app"
    password: "${ES_PASSWORD}"
    connection-timeout: 5s
    socket-timeout: 30s
  data:
    elasticsearch:
      repositories:
        enabled: false   # Lumix Repository minimal kullanır; full scan
```

### 6.2. Configuration

```java
@Configuration
@EnableElasticsearchRepositories(basePackages = "com.lumix.communication.search")
public class ElasticsearchConfig extends ElasticsearchConfiguration {

    @Value("${spring.elasticsearch.uris}")
    private String uri;

    @Value("${spring.elasticsearch.username}")
    private String username;

    @Value("${spring.elasticsearch.password}")
    private String password;

    @Override
    public ClientConfiguration clientConfiguration() {
        return ClientConfiguration.builder()
                .connectedTo(URI.create(uri).getAuthority())
                .usingSsl()
                .withBasicAuth(username, password)
                .withConnectTimeout(Duration.ofSeconds(5))
                .withSocketTimeout(Duration.ofSeconds(30))
                .build();
    }
}
```

### 6.3. Tenant-scoped search executor

```java
package com.lumix.communication.search;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TenantScopedSearchExecutor {

    private final ElasticsearchOperations operations;
    private final TenantContext tenantContext;

    public <T> SearchHits<T> search(NativeQueryBuilder builder, Class<T> docType) {
        String tenantId = tenantContext.currentTenantId().toString();

        Query existing = builder.build().getQuery();
        Query scoped = Query.of(q -> q.bool(b -> {
            b.filter(f -> f.term(t -> t.field("tenant_id").value(tenantId)));
            if (existing != null) {
                b.must(existing);
            }
            return b;
        }));

        NativeQuery finalQuery = builder.withQuery(scoped).build();
        return operations.search(finalQuery, docType);
    }
}
```

### 6.4. Domain use case

```java
package com.lumix.communication.application;

import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.SearchHits;

@Service
@RequiredArgsConstructor
public class MessageSearchUseCase {

    private final TenantScopedSearchExecutor executor;

    public MessageSearchResult search(MessageSearchQuery q) {
        var builder = NativeQuery.builder()
                .withQuery(query -> query.bool(b -> {
                    if (q.fullText() != null) {
                        b.must(m -> m.multiMatch(mm -> mm
                                .query(q.fullText())
                                .fields("body^2", "subject^3", "sender_name")
                                .fuzziness("AUTO")));
                    }
                    if (q.conversationId() != null) {
                        b.filter(f -> f.term(t -> t.field("conversation_id")
                                .value(q.conversationId().toString())));
                    }
                    if (q.fromDate() != null) {
                        b.filter(f -> f.range(r -> r.field("sent_at")
                                .gte(JsonData.of(q.fromDate().toString()))));
                    }
                    return b;
                }))
                .withSort(s -> s.field(f -> f.field("sent_at")
                        .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                .withPageable(PageRequest.of(q.page(), q.size()))
                .withHighlightQuery(buildHighlight());

        SearchHits<MessageDocument> hits = executor.search(builder, MessageDocument.class);

        return MessageSearchResult.from(hits);
    }

    private HighlightQuery buildHighlight() {
        return new HighlightQuery(
                new Highlight(List.of(
                        new HighlightField("body"),
                        new HighlightField("subject")
                )),
                MessageDocument.class
        );
    }
}
```

### 6.5. Controller

```java
@RestController
@RequestMapping("/api/v1/messages/search")
@RequiredArgsConstructor
public class MessageSearchController {

    private final MessageSearchUseCase useCase;

    @GetMapping
    @PreAuthorize("hasAuthority('messages:read')")
    public ResponseEntity<MessageSearchResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID conversationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        MessageSearchResult result = useCase.search(new MessageSearchQuery(
                q, conversationId, from, page, Math.min(size, 100)));

        return ResponseEntity.ok(MessageSearchResponse.from(result));
    }
}
```

### 6.6. Integration test (Testcontainers)

```java
@SpringBootTest
@Testcontainers
class MessageSearchIT {

    @Container
    static ElasticsearchContainer es = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.15.0")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", es::getHttpHostAddress);
    }

    @Autowired ElasticsearchOperations operations;
    @Autowired MessageSearchUseCase useCase;

    @Test
    void shouldFindMessageByBody() {
        var doc = new MessageDocument(
                "1", "abc-123", "11A", "u1", "Hüseyin",
                "Yarınki matematik sınavı çok önemli",
                List.of(), Instant.now(), 1L);
        operations.save(doc);
        operations.indexOps(MessageDocument.class).refresh();

        TenantContextHolder.set(UUID.fromString("abc-123"));

        var result = useCase.search(new MessageSearchQuery(
                "matematik", null, null, 0, 20));

        assertThat(result.totalHits()).isEqualTo(1);
    }
}
```

## 7. Dikkat edilecek tuzaklar

- **Tenant scope filter unutma**. Otomatik enforcement layer kullan; her query'de manuel yazma.
- **Deep pagination**. `from + size` 10000'ı geçemez. `search_after` kullan.
- **Source filtering eksik**. Büyük document'lerde sadece gerekli field'ları döndür (`withFields`).
- **Aggregation cardinality patlaması**. Terms aggregation `size: 10000` koymak cluster'ı çökertir.
- **Repository ile complex query**. Method name long ve fragile olur. Native query daha iyi.
- **Test'te `_refresh` atlama**. ES default 1s refresh; test yazıp hemen okumak başarısız olur.
- **HTTPS sertifika doğrulama atlama**. Production'da `usingSsl()` + truststore zorunlu.
- **Read-only user kullanma**. Search service kullanıcısı sadece read; index/write için ayrı credential.
- **Spring Data ES versiyon uyumsuzluğu**. ES 8.x ile compatible client kullan.
- **High score thresholding**. Düşük score'lu sonuçları filter etmek için `min_score` veya post-filter.
- **Highlight overhead**. Aggressive fragment size + multiple field highlight CPU yer.
- **Multi-tenant alias karışıklığı**. Tek alias + tenant_id filter; tenant başına ayrı index Lumix scale'de overhead.

## 8. Diğer konularla ilişkisi

- [Elasticsearch Temelleri](./elasticsearch-fundamentals)
- [Indexing Stratejisi](./indexing-strategy) — projection sağlanması
- [Hexagonal Architecture](../02-architecture-patterns) — port + adapter ayrımı
- [Multi-tenancy](../01-tenancy-and-domain-model/installation-tenant-scope) — tenant scope enforcement
- [Backend (gRPC)](../03-backend) — domain spesifik integration
- [Authentication](../04-authentication-authorization) — `@PreAuthorize`

## 9. Daha derine inmek için

- Spring Data Elasticsearch — [Reference](https://docs.spring.io/spring-data/elasticsearch/reference/index.html)
- Elastic — [Java API Client](https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/index.html)
- Elastic — [Search after for deep pagination](https://www.elastic.co/guide/en/elasticsearch/reference/current/paginate-search-results.html#search-after)
- Elastic — [Aggregations reference](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations.html)
- Araştırma keyword'leri: `spring data elasticsearch native query`, `elasticsearch tenant isolation pattern`, `elasticsearch java api client builder`, `search after vs from size`

## 10. Sözlük

- **Spring Data Elasticsearch** — Spring'in ES için ORM-benzeri abstraction'ı.
- **ElasticsearchOperations** — Düşük seviye Spring template, native query gücü ile.
- **ElasticsearchRepository** — Spring Data interface; CRUD + finder.
- **NativeQuery** — Spring Data ES'in builder DSL'i; ES Query DSL'i Java type-safe karşılığı.
- **SearchHits** — Spring Data ES sonuç wrapper'ı; score, highlight, total hits içerir.
- **Bool query** — `must`, `should`, `filter`, `must_not` clause'larından oluşan compound query.
- **Match query** — Analyzer'dan geçen full-text query.
- **Term query** — Exact match query, analyzer'dan geçmez (keyword field için).
- **Aggregation** — Sum, avg, terms, histogram gibi analytical operasyonlar.
- **Highlight** — Match'leyen kelimeleri sonuç içinde işaretleyen feature.
- **Pagination (from/size)** — Standart pagination; deep limit 10000.
- **search_after** — Deep pagination için cursor-based alternatif.
- **Tenant scope filter** — Her sorguda `tenant_id` filter zorunluluğu; defense-in-depth.
