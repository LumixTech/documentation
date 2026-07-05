---
title: Java 25 & Virtual Threads
description: Java 25 LTS yenilikleri (structured concurrency stable, scoped values stable, sequenced collections), Project Loom virtual threads (Java 21'den itibaren stable), klasik platform thread ile karşılaştırma ve Lumix'te kullanım stratejisi.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Bu sayfa **Java 25 LTS**'in neden seçildiğini, getirdiği yeni özellikleri (özellikle **virtual threads / Project Loom** — Java 21'den beri stable) ve Lumix'te bu özelliklerin pratikte nasıl kullanıldığını anlatıyor. Sayfanın merkezinde **virtual thread** kavramı var: nedir, klasik thread'den ne farkı var, ne zaman büyük kazanç sağlar, ne zaman değer üretmez. Sayfayı bitiren biri "Lumix'te neden bloglarda yazılan 100K-eşzamanlı request senaryolarına ulaşabiliyoruz, ama ne zaman dikkatli olmamız gerekir" sorularına cevap verebilmeli.

## 1. Bu nedir? (Sıfırdan)

### Java 25 LTS — Eylül 2025

**LTS (Long-Term Support)** = uzun süreli destekli sürüm. Java'da LTS'ler 2 yılda bir çıkar:
- Java 8 (2014, deprecated)
- Java 11 (2018)
- Java 17 (2021)
- Java 21 (2023) — virtual threads stable burada geldi
- **Java 25 (2025)** ← Lumix

Java 25 desteği **~2030'a** kadar (Oracle Premier + extended). Lumix dev/prod tüm yerde Java 25 kullanıyor. ADR-002: Java 25 LTS seçildi; Java 21 LTS de geçerli alternatifti, sonraki LTS migration sprint'inde 21→25 yerine doğrudan 25 ile başlamak tercih edildi.

### Modern Java'nın büyük yenilikleri (Java 21+ ile başlayan, Java 25'te stabilize olan)

| Özellik | JEP | Lumix kullanım |
|---|---|---|
| **Virtual Threads** | JEP 444 | ★★★ Yoğun kullanım |
| Pattern Matching for switch | JEP 441 | Aktif kullanım |
| Record Patterns | JEP 440 | Aktif kullanım |
| Sequenced Collections | JEP 431 | Yer yer kullanım |
| Generational ZGC | JEP 439 | GC stratejisi |
| String Templates (preview) | JEP 430 | Henüz yok |
| Structured Concurrency (Java 25'te stable) | JEP 453 → JEP 505 | Aktif kullanım |
| Scoped Values (Java 25'te stable) | JEP 446 → JEP 506 | Aktif kullanım (thread-local yerine) |

### Virtual Thread — günlük hayattan analoji

Klasik thread = **garson.** Restoranda her masaya bir garson atanır. 100 masa için 100 garson lazım. Garsonlar pahalı (her biri ayrı maaş, ayrı eğitim). Restoran 1000 masaya büyürse 1000 garson çalıştıramazsın.

Virtual thread = **sipariş kartı.** Garson hala 10 kişi, ama her masa için ayrı bir sipariş kartı var. Garson masaya gider, siparişi karta yazar (bu hızlı), sonra mutfağa götürür (yemek hazırlanırken garson başka kartla başka masaya gider). Bin masa için bin sipariş kartı tutabilirsin, garson sayısı sabit.

Virtual thread fikri: **JVM içinde milyonlarca "iş kartı" (virtual thread) tutabilirsin**, JVM bunları **az sayıda gerçek OS thread**'e (platform thread, "carrier") atar. İş "bekleme" haline geçince (DB query, HTTP call), virtual thread suspend olur, carrier serbest kalır, başka virtual thread'i taşır. İş tamamlanınca virtual thread devam eder.

### Klasik thread vs Virtual thread

| Özellik | Platform Thread | Virtual Thread |
|---|---|---|
| Memory cost | 1-2 MB stack | ~200 byte (heap'te) |
| Yaratım maliyeti | Yavaş (OS call) | Çok hızlı (in-JVM) |
| Maximum sayı | ~10,000 | Milyonlarca |
| Scheduler | OS scheduler | JVM ForkJoinPool |
| Blocking I/O | OS thread bloklanır | Carrier serbest kalır |
| Use case | CPU-intensive iş | I/O-intensive iş |

### Yazım açısından fark: hiç yok

```java
// Klasik thread
Thread t1 = new Thread(() -> doWork());
t1.start();

// Virtual thread
Thread t2 = Thread.ofVirtual().start(() -> doWork());
// VEYA
Thread t3 = Thread.startVirtualThread(() -> doWork());
```

`Thread` API aynı. `Runnable`, `ExecutorService`, `Callable`, `CompletableFuture` — hepsi virtual thread ile çalışır.

```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
// Veya Spring Boot 3.6'da otomatik (Spring Boot 3.2'den beri)
```

## 2. Hangi problemi çözüyor?

Virtual thread olmadan, Java'da yüksek concurrency için iki kötü seçenek vardı:

**Seçenek A: Çok thread aç — thread-per-request.**
Her HTTP isteği için bir thread. Tomcat default 200 thread. Aşıldıktan sonra istekler kuyrukta bekler. 1000 eşzamanlı request → 800 bekliyor, timeout.

Çözüm: thread pool'u büyüt. 1000 thread? OS bunu kaldırır ama her biri 1MB stack = 1GB sadece thread için. Memory kabusa.

**Seçenek B: Reactive programlama (WebFlux, Project Reactor).**
Non-blocking I/O ile az thread ile çok iş yap.

Sorunlar:
- Reactive kod karmaşık (Mono, Flux, operator chain'ler)
- Debug zor (stack trace anlaşılmaz)
- "Backpressure" gibi yeni kavramlar
- Imperative kodu reactive'e çevirmek aylar sürer
- Reactive'de bir blocking çağrı pipeline'ı çökertir

**Acı 1 — "Senin app'in 200 RPS'i geçemez."**
Tomcat default 200 thread + her request 300ms (DB call + Kafka publish) = max 666 RPS. Müşteri 1000 RPS istiyor. Sen scale-out diye 5 pod açıyorsun. Maliyet 5x.

**Acı 2 — Reactive kabusu.**
"WebFlux'a geçelim diyelim." Tüm controller, service, repository reactive. Aylar sonra debug imkansız. Stack trace 30 satır, hiçbiri senin kodun değil.

**Acı 3 — Async + blocking karıştırması.**
`@Async` kullanırsın, executor'da blocking call yaparsın. Thread pool'u tüketirsin. Cascading failure.

**Acı 4 — Thread pool exhaustion.**
Database yavaşladı, tüm thread'ler "DB cevabını bekliyor" durumunda kilitli. Yeni request alınamıyor. Cascading.

Virtual thread bu acıları şöyle çözer:

| Acı | Virtual Thread çözümü |
|---|---|
| 200 RPS limiti | Milyon virtual thread, blocking I/O carrier'ı bloklamaz |
| Reactive kabusu | Imperative, blocking kod ile aynı throughput |
| Async + blocking karışıklığı | Virtual thread bloklanmak için yapıldı |
| Thread pool exhaustion | Virtual thread tükenmez (heap kadar) |

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Virtual thread'in iç işleyişi

```
                ┌─────────────────────────────────────────┐
                │      ForkJoinPool (carrier threads)     │
                │   (genelde CPU sayısı kadar OS thread)  │
                │                                          │
                │   carrier-1   carrier-2   carrier-3      │
                │      │           │           │           │
                │      ▼           ▼           ▼           │
                │   [running] [running] [parking]          │
                └──────┬───────────┬───────────┬───────────┘
                       │           │           │
                       │           │           │
              ┌────────┴───────────┴───────────┴───────────────┐
              │                                                 │
              │       Virtual Threads (heap'te)                 │
              │                                                 │
              │   v1 v2 v3 v4 v5 ... v999998 v999999 v1000000  │
              │   ↑                                             │
              │   binlerce/milyonlarca                          │
              └─────────────────────────────────────────────────┘
```

**Akış:**
1. Request gelir, JVM yeni virtual thread (`v1`) yaratır
2. `v1` carrier-1'e mount edilir, kod çalışır
3. `v1` blocking DB call yapar (`jdbc.query(...)`)
4. JVM bunu yakalar: `v1` **unmount** olur, carrier-1 serbest
5. carrier-1 başka virtual thread (`v2`)'yi mount eder
6. DB cevap geldiğinde: `v1` herhangi bir uygun carrier'a mount edilir, devam eder
7. Request bitince `v1` ölür (garbage collect edilir)

### 3.2. Hangi operasyonlar virtual-thread aware?

Java standard library (Java 21 itibarıyla) çoğunlukla virtual thread'i destekler:
- `java.net.http.HttpClient` — async lookup
- `java.io` — InputStream, OutputStream (NIO underlying)
- `java.nio` — non-blocking
- `java.util.concurrent.locks.LockSupport` — park/unpark
- JDBC (driver'a bağlı) — modern driver'lar destekler
- `Thread.sleep`, `Object.wait`, blocking queue methods — destekler

**Native operations (JNI, native lock):**
Virtual thread bunlarda **pinned** olur — carrier üzerinde takılı kalır, suspend olamaz. Bu durumda virtual thread avantajı kaybolur.

### 3.3. Pinning — virtual thread'in en büyük tuzağı

**Pinning durumları:**
1. `synchronized` blok içinde blocking call → pin
2. JNI call içinde → pin
3. Class initializer'da → pin

Pin olunca virtual thread carrier'ı bırakamaz. Eğer çok pin olursa: carrier'lar tükenir, throughput çöker.

**Çözüm:**
- `synchronized` yerine `ReentrantLock` kullan
- Hot path'te native call'dan kaçın
- `-Djdk.tracePinnedThreads=full` ile pin'leri debug et

### 3.4. Structured concurrency (preview)

Java 21'de **preview** olarak başlayan `StructuredTaskScope`, Java 25'te **stable**:

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<User> user = scope.fork(() -> findUser(userId));
    Subtask<Order> orders = scope.fork(() -> findOrders(userId));
    scope.join();           // hepsi bitsin
    scope.throwIfFailed();  // herhangi biri fail ettiyse exception
    return new Dashboard(user.get(), orders.get());
}
```

Avantajı: paralel iş yaparken **scope bitiminde** kaynaklar temizlenir, error handling net.

Lumix'te aktif kullanım (Java 25'te stable; production'da güvenle kullanılır).

### 3.5. Generational ZGC

Java 21 ile **Generational ZGC** stable. ZGC = sub-millisecond pause time'lı GC.

Lumix prod'da:
```
-XX:+UseZGC -XX:+ZGenerational
```

Heap large ise (8GB+) önemli kazanç. Latency-sensitive servisler (identity, payment) için tercih.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Spring Boot 3.6 + virtual threads

Spring Boot 3.6 virtual thread'i tek satır config ile enable eder (özellik Spring Boot 3.2'den beri var):

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Bunun sonucu:
- Tomcat request executor virtual thread per request
- `@Async` methodları virtual thread'te
- `@Scheduled` methodları virtual thread'te
- `KafkaListener` virtual thread'te (config edilirse)
- `WebClient` (block ettiğinde) virtual thread'le çalışır

### 4.2. Lumix'te virtual thread kullanım alanları

**Yoğun virtual thread kullanımı:**
- Web request handling (Tomcat thread → virtual thread)
- gRPC server (`grpc-java` 1.66+ destek)
- Kafka consumer (her record processing virtual thread'te)
- Async I/O (HTTP client, external API call)
- Outbox relay batch processing
- File processing (S3 upload/download)

**Virtual thread kullanmıyoruz:**
- CPU-intensive iş (encoding, image processing) → platform thread executor
- Belirli legacy native lib çağrıları (pin sorunu)

### 4.3. Yapılandırma örneği

```java
@Configuration
public class AsyncConfig {

    @Bean
    public TaskExecutor virtualThreadExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }

    @Bean(name = "cpuIntensiveExecutor")
    public TaskExecutor cpuIntensiveExecutor() {
        return new ThreadPoolTaskExecutor() {{
            setCorePoolSize(Runtime.getRuntime().availableProcessors());
            setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 2);
            setQueueCapacity(100);
            setThreadNamePrefix("cpu-");
            initialize();
        }};
    }
}
```

Kullanım:
```java
@Service
public class ReportService {

    @Async  // default = virtual thread
    public CompletableFuture<Void> generateLightReport(...) { ... }

    @Async("cpuIntensiveExecutor")  // explicit CPU pool
    public CompletableFuture<byte[]> renderPdf(...) { ... }
}
```

### 4.4. Kafka consumer virtual thread

```java
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
        ConsumerFactory<String, byte[]> consumerFactory
    ) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);  // 3 partition consumer thread

        // Each record processing runs on virtual thread
        factory.getContainerProperties().setListenerTaskExecutor(
            new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor())
        );
        return factory;
    }
}
```

### 4.5. JDBC driver dikkat

Lumix PostgreSQL JDBC driver: **`pgjdbc-ng`** veya **`postgresql`** (latest).
Latest `postgresql` driver virtual thread aware.

Connection pool: **HikariCP** virtual thread ile uyumlu (pool size pin'lemez).

### 4.6. Monitoring

Virtual thread metric'leri:
- JVM `Thread.activeCount()` artık anlamsız (carrier'ları sayar, vt'leri saymaz)
- JFR (Java Flight Recorder) virtual thread event'leri yayınlar
- `jcmd Thread.dump_to_file` ile virtual thread dump

Prometheus ile:
```
jvm_threads_states_threads{state="runnable"}
jvm_threads_live_threads
jvm_threads_peak_threads
```

Lumix Grafana dashboard'da takip ediyor.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Java 17 / Java 21 vs Java 25

Java 17 (eski LTS, 2021):
- Virtual threads yok
- Pattern matching daha kısıtlı
- ZGC yok generational

Java 21 (önceki LTS, 2023):
- Virtual threads stable
- Pattern matching for switch + record patterns stable
- Generational ZGC stable
- Structured concurrency, scoped values **preview**
- 2028 sonuna kadar destek

Lumix Java 25 (yeni LTS, 2025):
- Java 21'in tüm kazanımları (virtual threads, ZGC, pattern matching)
- Structured concurrency **stable** (JEP 505)
- Scoped values **stable** (JEP 506) — thread-local yerine
- Compact source files + instance main methods (öğrenme/script için)
- Sequenced collections, key derivation API
- ~2030'a kadar destek

Maliyet: takım Java 25'i öğrenmeli (Java 21'le aynı mental model, ek olarak structured concurrency + scoped values stable kullanımı).

### 5.2. Virtual Threads vs WebFlux (Reactive)

| Kriter | Virtual Threads | WebFlux |
|---|---|---|
| Kod stili | Imperative, blocking | Reactive, async chains |
| Learning curve | Düşük | Yüksek |
| Debug | Stack trace okunaklı | Stack trace karışık |
| Throughput | I/O bound: yüksek | I/O bound: yüksek |
| Mevcut kodla uyum | ★★★ | ★ (refactor zor) |
| Ekosistem | Spring 3.6 native | Spring WebFlux ayrı stack |
| Performans (raw) | Az daha az | Az daha çok (no JVM bookkeeping) |

**Lumix tercih:** Virtual threads. Sebep:
- Mevcut Spring MVC code base
- Imperative kod ekibe tanıdık
- Debug kolaylığı
- Throughput WebFlux'a yakın

### 5.3. ZGC vs G1GC

Lumix prod'da:
- **Identity, payment** (latency-sensitive): ZGC + Generational
- **Reporting, batch** (throughput-oriented): G1GC

ZGC avantaj: sub-ms pause.
ZGC dezavantaj: throughput biraz düşük (G1'e göre %10), memory overhead.

### 5.4. Kabul edilen trade-off'lar

| Trade-off | Maliyet | Mitigation |
|---|---|---|
| `synchronized` pin'i | Hot path'te dikkat | ReentrantLock kullanımı |
| Stack trace büyük | Debug daha çok scroll | Filter ile gizleme |
| Virtual thread overhead var | Çok kısa görevler için fayda yok | CPU-intensive için platform thread |
| Henüz yeni | Production deneyim sınırlı | Monitoring + alert |
| Memory profile farklı | OldGen'de virtual thread struct'ları | ZGC ile dengelendi |

## 6. Pratik örnek

### 6.1. Basit kullanım — Spring Boot

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true
```

Bu tek satır:
- Tomcat virtual thread per request
- `@Async` virtual thread executor
- `@Scheduled` virtual thread

### 6.2. Manuel virtual thread executor

```java
@Service
@RequiredArgsConstructor
public class ReportGenerator {

    private static final ExecutorService VIRTUAL_EXECUTOR =
        Executors.newVirtualThreadPerTaskExecutor();

    public CompletableFuture<Report> generate(List<StudentId> students) {
        return CompletableFuture.supplyAsync(() -> {
            // Her öğrenci için paralel fetch
            try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
                Map<StudentId, Subtask<StudentSummary>> tasks = new HashMap<>();
                for (StudentId s : students) {
                    tasks.put(s, scope.fork(() -> fetchStudentSummary(s)));
                }
                scope.join();
                scope.throwIfFailed();

                Map<StudentId, StudentSummary> results = tasks.entrySet().stream()
                    .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().get()
                    ));
                return Report.from(results);
            } catch (Exception ex) {
                throw new ReportGenerationException(ex);
            }
        }, VIRTUAL_EXECUTOR);
    }

    private StudentSummary fetchStudentSummary(StudentId id) {
        // Bu method blocking DB call yapar - virtual thread carrier'ı bırakır
        return studentRepo.findSummary(id);
    }
}
```

### 6.3. Performance test örneği (öncesi sonrası)

```java
@Test
void throughputUnderLoad() {
    int requests = 10_000;
    CountDownLatch latch = new CountDownLatch(requests);
    AtomicLong succeeded = new AtomicLong();

    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    long start = System.currentTimeMillis();

    for (int i = 0; i < requests; i++) {
        executor.submit(() -> {
            try {
                // Simulated I/O — 100ms DB call
                Thread.sleep(100);
                succeeded.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                latch.countDown();
            }
        });
    }

    latch.await(60, TimeUnit.SECONDS);
    long duration = System.currentTimeMillis() - start;
    // Platform thread (200 limit): ~5000ms (50 round of 200)
    // Virtual thread: ~150ms (hepsi paralel)
    assertThat(succeeded.get()).isEqualTo(requests);
    assertThat(duration).isLessThan(500);
}
```

### 6.4. Pinning'i tespit etmek

```bash
java -Djdk.tracePinnedThreads=full -jar app.jar
```

Stdout'a şu çıkar:
```
Thread[VirtualThread-23,5,main] is pinned at:
    java.base/java.lang.Object.wait(Native Method)
    com.lumix.legacy.OldLockManager.acquire(OldLockManager.java:55)
        <== monitors:1
```

`<== monitors:1` = `synchronized` blok yüzünden pin.

### 6.5. synchronized → ReentrantLock dönüşümü

**Yanlış (pin yaratır):**
```java
private final Object lock = new Object();

public void process() {
    synchronized (lock) {
        // blocking I/O
        externalApi.call();
    }
}
```

**Doğru (pin yok):**
```java
private final ReentrantLock lock = new ReentrantLock();

public void process() {
    lock.lock();
    try {
        externalApi.call();
    } finally {
        lock.unlock();
    }
}
```

### 6.6. Record pattern + virtual thread

Java 21 record pattern:

```java
public sealed interface Result permits Success, Failure {}
public record Success(Object value) implements Result {}
public record Failure(Exception cause) implements Result {}

public Result process(Request req) {
    return switch (handle(req)) {
        case Success(var value) -> {
            log.info("Success: {}", value);
            yield new Success(value);
        }
        case Failure(var cause) when cause instanceof TransientException -> {
            // retry
            yield process(req);
        }
        case Failure(var cause) -> {
            log.error("Permanent failure", cause);
            yield new Failure(cause);
        }
    };
}
```

### 6.7. JVM startup flags (Lumix prod)

```bash
JAVA_OPTS="
  -XX:+UseZGC
  -XX:+ZGenerational
  -XX:MaxRAMPercentage=75.0
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/var/log/heap-dump.hprof
  -XX:+ExitOnOutOfMemoryError
  -Djdk.tracePinnedThreads=short
  -Dfile.encoding=UTF-8
  -Duser.timezone=UTC
"
```

## 7. Dikkat edilecek tuzaklar

**Tuzak 1 — `synchronized` ile virtual thread.**
Eski kod `synchronized` blok'larıyla dolu. Virtual thread pin olur, kazanç yok.
**Önleme:** `synchronized` → `ReentrantLock`. `-Djdk.tracePinnedThreads=full` ile tespit.

**Tuzak 2 — Virtual thread pool yaratmak.**
"Pool gibi kullanayım" diye `ExecutorService` ile fixed size virtual thread executor.
**Önleme:** Virtual thread pool ANTI-PATTERN. `newVirtualThreadPerTaskExecutor()` zaten her task için yeni vt.

**Tuzak 3 — CPU-intensive iş virtual thread'te.**
PDF rendering, image resize gibi CPU işi virtual thread'te. Carrier'ları tüketir.
**Önleme:** CPU iş için ayrı platform thread pool.

**Tuzak 4 — Thread-local kullanımı.**
Çok virtual thread → çok thread-local entry → memory patlar.
**Önleme:** Java 25'in `ScopedValue`'sini kullan (Java 25'te stable, Java 21'de preview olarak gelmişti). Veya thread-local'i minimize et.

**Tuzak 5 — Connection pool size yanlış.**
"Çok thread var, çok connection açalım" — DB connection pool 1000'e çıkar, DB çöker.
**Önleme:** Pool size DB kapasitesine göre. Virtual thread sayısı != connection sayısı.

**Tuzak 6 — Native blocking call.**
Eski JNI lib, NIO olmayan I/O — carrier'ı blokla.
**Önleme:** Modern Java API tercih et. Native call'larda dikkat.

**Tuzak 7 — Spring kütüphane uyumsuzluğu.**
Bazı eski Spring projeleri (Spring AMQP eski versiyonları) virtual thread'le çakışabilir.
**Önleme:** Spring 6.2+ + Spring Boot 3.6+ kullan. Sürüm sıkılığı.

**Tuzak 8 — Long-running task'larda monitoring eksiği.**
Milyon virtual thread var ama hangileri uzun süredir bekliyor görünmüyor.
**Önleme:** JFR + Mission Control. `jcmd <pid> Thread.dump_to_file` ile snapshot.

**Tuzak 9 — Garbage collection pressure.**
Çok virtual thread create/destroy = GC yükü artar.
**Önleme:** ZGC veya G1 + heap tuning. Lumix ZGC kullanıyor.

**Tuzak 10 — "Performance otomatik" varsayımı.**
Virtual thread enable ettim, throughput artmadı. Sebep: zaten CPU bound'tum.
**Önleme:** Profile first. Throughput problemi I/O bound ise virtual thread çare. CPU bound ise scale-out lazım.

**Tuzak 11 — Reactive kodla karıştırma.**
Bir kısım WebFlux, bir kısım MVC. Karmaşa.
**Önleme:** Lumix tek bir stack: Spring MVC + virtual thread. Reactive yok.

**Tuzak 12 — Preview feature'ları production'da.**
Structured concurrency, scoped value, string templates — preview. Production'da kullanırsan Java versiyon upgrade kırabilir.
**Önleme:** Stable feature'lara güven. Preview'lar pilot/lab için.

## 8. Diğer konularla ilişkisi

- [Spring Boot Foundation](./01-spring-boot-foundation) — Java 25 + Spring Boot 3.6 birlikte
- [gRPC Service Communication](./03-grpc-service-communication) — virtual thread'le gRPC
- [Microservices Architecture](../02-architecture-patterns/01-microservices-architecture) — virtual thread per request
- [Outbox Pattern](../02-architecture-patterns/06-outbox-pattern) — relay scheduler virtual thread'te
- [Event-Driven Architecture](../02-architecture-patterns/04-event-driven-architecture) — Kafka consumer virtual thread

## 9. Daha derine inmek için

**Resmi:**
- openjdk.org/jeps/444 — Virtual Threads JEP
- openjdk.org/projects/loom — Project Loom
- inside.java/2023/09/19/sip084 — Brian Goetz tarafından virtual threads anlatımı
- docs.oracle.com/en/java/javase/25

**Kitaplar:**
- "Modern Java in Action" — Raoul-Gabriel Urma
- "Java Concurrency in Practice" — Brian Goetz (klasik, virtual thread öncesi ama temel kavramlar)

**Spring + virtual thread:**
- spring.io/blog/2022/10/11/embracing-virtual-threads
- docs.spring.io/spring-boot/docs/3.3.x/reference/html/features.html#features.task-execution-and-scheduling

**Search keywords:**
- "java 21 virtual threads project loom"
- "spring boot 3.3 virtual threads"
- "virtual thread pinning synchronized"
- "structured concurrency java 21"
- "zgc generational java 21"
- "java 21 record patterns"

## 10. Sözlük

- **Carrier Thread** — Virtual thread'i çalıştıran platform thread.
- **Continuation** — Virtual thread'in askıya alıp devam etme mekanizması.
- **ForkJoinPool** — Java'nın work-stealing thread pool'u. Virtual thread scheduler.
- **Generational ZGC** — ZGC'nin young/old generation desteği.
- **JEP** — JDK Enhancement Proposal — Java yeniliği önerisi.
- **JFR (Java Flight Recorder)** — JVM event recording aracı.
- **LTS** — Long Term Support sürüm.
- **Mount/Unmount** — Virtual thread'in carrier'a bağlanması/ayrılması.
- **Pattern Matching for switch** — Java 21'den itibaren stable (Java 25'te ek iyileştirmeler), type pattern + record pattern.
- **Pinning** — Virtual thread'in carrier üzerinde kilitli kalması (synchronized, native call).
- **Platform Thread** — Klasik OS thread.
- **Project Loom** — Virtual thread + structured concurrency umbrella projesi.
- **Record Pattern** — Record destructuring (`case Point(int x, int y)`).
- **ScopedValue** — ThreadLocal'in virtual thread alternatifi (preview).
- **Structured Concurrency** — Paralel görevleri scope'lu yönetme (preview).
- **Virtual Thread** — JVM-managed lightweight thread.
- **ZGC** — Z Garbage Collector, sub-ms pause hedefli.
