---
title: PostgreSQL Composite Index Sıralaması
description: B-tree composite index kolon sırasının etkisi ve tenant_id-first sorgu tasarımı yaklaşımı.
sidebar_position: 1
---

## Giriş

Composite index sadece performans ayarı değildir; veri erişim modelinin parçasıdır. PostgreSQL B-tree yapısında kolon sırası, indeksin hangi sorgularda etkin olacağını doğrudan belirler.

## Neden Önemli

Yanlış sıralama:

- geniş taramalar,
- dalgalı sorgu gecikmeleri,
- düşük faydalı yüksek maliyetli indeksler üretir.

## Temel Kavramlar

- B-tree index: sıralı ağaç tabanlı indeks.
- Composite index: çok kolonlu indeks.
- Leftmost-prefix davranışı: öndeki kolonlardan başlayan filtrelerde daha yüksek etkinlik.
- Planner seçimi: predicate yapısına göre index scan veya seq scan kararı.

## Gerçek Ürün Geliştirmede Problem

Sorgular tenant odaklıyken `(user_id, tenant_id)` gibi ters sıralama yapmak, indeksin beklenen faydasını düşürür.

## Yaklaşım / Tasarım

Örnek:

```sql
CREATE INDEX idx_orders_tenant_user ON orders (tenant_id, user_id);
```

Mantık:

- Önce `tenant_id` ile veri kümesi daraltılır.
- Sonra `user_id` ile tenant içi seçim yapılır.

Telefon rehberi analojisi:

- Rehber soyada göre sıralıysa önce soyadla daraltma hızlıdır.

## Sektör Standardı / Best Practice

- İndeksi tablo kolon dizilişine göre değil, query pattern'e göre tasarla.
- `EXPLAIN (ANALYZE, BUFFERS)` ile doğrula.
- Yazma maliyeti (index maintenance) ile okuma kazancını birlikte değerlendir.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
for each critical_query:
  predicates = extract_predicates(critical_query)
  lead = choose_leading_column(predicates, tenant_scope_first=true)
  candidate_index = compose_index(lead, next_best_column(predicates))

  if explain_analyze_improves(candidate_index, critical_query):
    keep_index
  else:
    revise_order
```

## Bizim Notlarımız / Takım Kararları

- Filtreleme düşüncemiz `tenant_id` ile başlıyor.
- Bu nedenle tenant-scope tablolarında composite index'in ilk kolonu çoğu durumda `tenant_id` olmalıdır.
- Tenant sınır güvenliği [Tenant-based Row-Level Security (RLS)](./tenant-based-rls-policy-design) ile birlikte ele alınmalıdır.

## Sözlük

- Selectivity: filtrenin satır daraltma gücü.
- Cardinality: kolon değer çeşitliliği.
- Index scan: indeks üzerinden satır erişimi.

## Araştırma Keywordleri

- `postgresql composite index order`
- `leftmost prefix btree`
- `tenant_id first index design`

## Sonuç

Doğru index sıralaması, çok kiracılı ürünlerde performans ve öngörülebilirlik için kritik mimari karardır.
