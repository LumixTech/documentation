---
title: "Redis Distributed Caching: Cache-Aside, Invalidation ve TTL Trade-off'ları"
description: Redis distributed caching, Cache-Aside akışı, cache hit/miss davranışı, invalidation karmaşıklığı ve TTL consistency kararları için mühendislik notu.
sidebar_position: 6
---

## Giriş

Distributed sistemlerde performans artırmanın en güçlü araçlarından biri caching'dir. Ancak cache eklemek sistemi sadece hızlandırmaz; karmaşıklığı da artırır.

Klasik uyarı şudur:

> "There are only two hard things in Computer Science: cache invalidation and naming things."

Bu doküman şu sorulara mühendislik perspektifiyle cevap verir:

- Cache neden vardır?
- Cache-Aside nasıl çalışır?
- Cache invalidation neden zordur?
- TTL ne zaman tercih edilir?

## Cache Neden Vardır?

Amaç basittir:

```text
Sık okunan ve pahalı olan veriyi daha düşük latency ile sunmak
```

Cache yokken:

```text
Database = Single Source of Truth
```

Redis cache eklendiğinde:

```text
Client -> App -> Redis -> DB
```

Artık sistemde aynı verinin birden fazla kopyası vardır:

```text
DB kopyası
Cache kopyası
Potansiyel response/projection kopyaları
```

Bu da temel problemi doğurur:

```text
Consistency
```

## Cache-Aside Stratejisi

Cache-Aside, lazy loading olarak da bilinir.

Akış:

```text
1. Request gelir
2. Application Redis'e bakar
3. Varsa -> cache'ten return edilir
4. Yoksa -> DB'den okunur
5. DB sonucu Redis'e yazılır
6. Response dönülür
```

Spring örneği:

```java
@Cacheable(value = "users", key = "#id")
public User getUserById(Long id) {
    return userRepository.findById(id).orElseThrow();
}
```

Cache policy'sinin sahibi application'dır. Redis ise storage ve expiry davranışını uygular.

## Cache Hit vs Cache Miss

### Cache Hit

```text
Client -> App -> Redis -> Response
```

Sonuç:

- düşük latency,
- DB load yok,
- daha düşük application işi.

### Cache Miss

```text
Client -> App -> Redis
               |
               v
              DB
               |
               v
            Redis'e yaz
               |
               v
            Response
```

Sonuç:

- daha yüksek latency,
- DB yükü,
- serialization ve network maliyeti,
- aynı anda çok sayıda miss olursa burst pressure riski.

## Cache'in Maliyeti

Cache bedava performans değildir.

Şunları beraberinde getirir:

- memory maliyeti,
- serialization maliyeti,
- network maliyeti,
- invalidation karmaşıklığı,
- stale data riski,
- operasyonel izleme ihtiyacı.

Kritik soru şudur:

> Bu veri gerçekten cache'e değer mi?

## Cache Invalidation Problemi

Örnek:

```text
Redis:
users::42 -> email=ali@example.com

Database:
users(42) -> email=ali.new@example.com
```

Redis güncellenmez veya silinmezse sistem hızlı ama yanlış veri döner.

Invalidation probleminin özü budur: performans ve doğruluk bazen ters yönlere çeker.

## Entity Cache vs List Cache

### Entity Cache

```text
products::123
```

Bu cache key tek bir entity'yi temsil eder.

### List / Query Cache

```text
homepageFeaturedProducts
categoryProducts::electronics::page=1
sellerProducts::sellerId=55
discountedProducts
```

Bu key'ler view veya query result temsil eder. İçlerinde birçok ürünün snapshot'ı bulunabilir.

## Yaygın Hata

Varsayım:

```text
Sadece products::123 silmek yeterlidir
```

Bu, list cache'ler eski ürün verisini içerdiğinde yanlıştır.

Örnek:

```text
homepageFeaturedProducts -> eski fiyat
categoryProducts -> eski fiyat
sellerProducts::sellerId=55 -> eski ürün adı
```

Entity cache doğru hale gelse bile view cache'ler stale kalabilir.

## Doğru Mental Model

Cache invalidation sadece şu sorudan başlamamalıdır:

```text
Hangi entity değişti?
```

Daha doğru soru şudur:

```text
Bu değişen entity hangi cache'lenmiş view'ların içinde yer alıyor?
```

Invalidation çoğu zaman sadece entity-level cleanup değil, view-level tasarım problemidir.

## TTL Stratejisi

TTL, Time-To-Live anlamına gelir.

Cache'lenmiş veriyi belirli bir süre sonra otomatik expire eder:

```text
TTL = 30 saniye
```

Maksimum stale data penceresi:

```text
30 saniye
```

TTL geçerli bir stratejidir, fakat bir consistency kararıdır. Sistem belirli bir süre stale data kabul ediyor demektir.

## TTL vs Manual Invalidation

| Özellik | TTL | CacheEvict / Manual Invalidation |
| --- | --- | --- |
| Basitlik | Yüksek | Daha düşük |
| Anlık doğruluk | Daha düşük | Daha yüksek |
| Operasyon yükü | Daha düşük | Daha yüksek |
| Kontrol | Sınırlı | Daha yüksek |
| Stale data riski | TTL ile sınırlı | Invalidation doğruluğuna bağlı |

## TTL Ne Zaman Doğru?

TTL, stale data kısa süreliğine kabul edilebiliyorsa uygundur.

Örnekler:

- 30 saniye toleranslı ürün listing'i,
- dashboard özetleri,
- analytics,
- feed'ler,
- search/filter result page'leri,
- public homepage blokları.

## TTL Ne Zaman Yanlış?

Strong consistency gerekiyorsa TTL tehlikelidir.

Örnekler:

- ödeme durumu,
- bakiye,
- authorization kararları,
- security-sensitive user state,
- fraud veya risk kararları,
- kesin olması gereken inventory operasyonları.

Bu alanlarda stale data business veya security incident'e dönüşebilir.

## Spring vs Redis Sorumluluğu

Doğru sorumluluk ayrımı şudur:

```text
Policy -> Application (Spring)
Execution -> Redis
```

Spring configuration örneği:

```java
RedisCacheConfiguration.defaultCacheConfig()
    .entryTtl(Duration.ofSeconds(30));
```

Application hangi cache'in var olduğunu ve ne kadar yaşayacağını belirler. Redis bu expiry policy'sini uygular.

## Granular TTL Tasarımı

Her cache aynı TTL'e sahip olmamalıdır.

Örnek policy:

```text
products             -> 30 sn
productDetails       -> 5 dk
homepageFeatured     -> 15 sn
categoryProducts     -> 60 sn
userSession          -> 30 dk
exchangeRates        -> 5 sn
```

TTL şu faktörlere göre belirlenmelidir:

- business criticality,
- update frequency,
- stale data tolerance,
- recomputation cost,
- consumer expectations.

## Trade-off Özeti

```text
TTL -> Basit ama eventual consistency
Manual eviction -> Daha doğru ama daha karmaşık
```

Mühendislik kararı "Redis var mı yok mu?" değildir. Asıl karar şudur:

```text
Latency mi, consistency mi?
```

Farklı domain'ler bu soruya farklı cevap verir.

## Sokratik Çıkarımlar

### Ders 1

```text
Cache sadece hız değil, veri kopyasıdır
```

### Ders 2

```text
Invalidation entity değil, view seviyesinde düşünülmelidir
```

### Ders 3

```text
Her şeyi cache'lemek doğru değildir
```

### Ders 4

```text
TTL bir kaçış değil, bilinçli bir consistency kararıdır
```

## Devam Konuları

Bu dokümanın doğal devamı şunlardır:

- Write-Through vs Write-Behind,
- Cache Stampede,
- Redisson ile Distributed Lock,
- cache race condition senaryoları,
- cache key naming strategy,
- Caffeine + Redis ile multi-level cache,
- Kafka ile event-driven invalidation.

## Araştırma Keywordleri

- `redis cache aside pattern spring boot`
- `cache invalidation best practices`
- `redis ttl tradeoffs`
- `spring cache redis ttl configuration`
- `distributed cache consistency`
- `cache stampede redis`

## Sonuç

Cache eklemek kolaydır. Doğru invalidation zordur.

İyi bir cache tasarımı; nerede eventual consistency kabul ettiğini, nerede anlık doğruluk istediğini ve source data değiştiğinde hangi cache'lenmiş view'ların invalidate edilmesi gerektiğini açıkça belirler.
