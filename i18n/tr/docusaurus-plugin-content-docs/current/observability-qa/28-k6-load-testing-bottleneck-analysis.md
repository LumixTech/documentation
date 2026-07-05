---
title: "k6 ile Yük Testi ve Darboğaz Analizi"
description: k6 scenarios, thresholds, ramping arrival rate ve 08:30 attendance peak testi için yük testi stratejisi.
sidebar_position: 3
---

## Giriş

Yük testi, kullanıcılar production'da darboğazı keşfetmeden önce sistemin gerçekçi trafiği kaldırıp kaldıramadığını doğrular.

Bu ürün için 08:30 attendance peak kritik senaryodur. Birçok öğretmen kısa bir zaman aralığında uygulamayı açabilir, classroom verisi yükleyebilir, yoklama işaretleyebilir ve submit edebilir.

## Neden Önemli

Peak traffic ortalama traffic değildir.

Sistem gün boyunca sağlıklı görünüp şu durumda fail olabilir:

- birçok kullanıcı aynı workflow'u aynı anda çalıştırır,
- cache entry'leri aynı anda expire olur,
- DB connection pool dolar,
- write lock birikir,
- notification job'ları request trafiğiyle yarışır,
- authentication refresh trafiği spike yapar.

k6; scenario, threshold ve ramping arrival rate ile capacity kararını kanıta dayalı hale getirir.

## Temel Kavramlar

- Virtual user: script çalıştıran simüle kullanıcı.
- Scenario: trafiğin nasıl üretileceğini tanımlayan workload modeli.
- Arrival rate: birim zamanda başlatılan iteration sayısı.
- Ramping arrival rate: arrival rate'in zaman içinde değiştiği model.
- Threshold: metric bazlı pass/fail koşulu.
- Check: test içindeki functional assertion.
- Bottleneck: throughput veya latency'yi sınırlayan component.
- Saturation: resource talebinin faydalı kapasiteyi aştığı durum.

## Gerçek Ürün Geliştirmede Problem

Basit testler gerçek riski kaçırır:

```text
GET /health 1000 defa
```

Bu attendance workflow'unu doğrulamaz. Gerçek senaryo authentication, classroom list read, student list read, attendance write, validation, DB transaction, outbox write ve notification adımlarını içerir.

## Yaklaşım / Tasarım

### 08:30 Attendance Peak Senaryosu

Varsayımlar configurable olmalıdır:

- active teacher sayısı,
- ramp-up window,
- attendance submit rate,
- sınıf başına ortalama öğrenci,
- authentication token reuse,
- target environment base URL.

Örnek traffic shape:

```text
08:25 - 08:29 warm-up browsing
08:30 - 08:35 yüksek attendance submission rate
08:35 - 08:40 düzeltme ve reload trafiği
```

### k6 Script Skeleton

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    attendance_peak: {
      executor: 'ramping-arrival-rate',
      timeUnit: '1m',
      preAllocatedVUs: 100,
      maxVUs: 500,
      stages: [
        { duration: '4m', target: 60 },
        { duration: '5m', target: 240 },
        { duration: '3m', target: 120 },
        { duration: '2m', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800', 'p(99)<1500'],
    'http_req_duration{endpoint:attendance_submit}': ['p(95)<1200'],
  },
};
```

Helper fonksiyonlar project-specific seeded user ve classroom fixture ile doldurulmalıdır.

### Darboğaz Analizi Checklist

Test sırasında şunlar izlenmelidir:

- API p95/p99 latency,
- HTTP failure rate,
- database CPU ve locks,
- database connection pool saturation,
- slow queries,
- Redis latency ve hit rate,
- message broker lag,
- JVM heap ve GC pressure,
- pod CPU throttling,
- autoscaling behavior.

## Sektör Standardı / Best Practice

- Sadece endpoint değil komple workflow test et.
- Threshold'ları release gate olarak kullan.
- Smoke, load, stress ve soak testleri ayır.
- Gerçekçi data volume ve tenant distribution kullan.
- Request'leri endpoint/use case tag'leriyle işaretle.
- k6 sonucunu metric ve trace'lerle ilişkilendir.
- Production-like infrastructure üzerinde çalıştır.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
run_load_test(scenario):
  prepare_seed_data()
  start_observability_dashboard()
  execute_k6_scenario()
  collect_k6_summary()
  inspect_traces_for_slowest_requests()
  inspect_db_for_locks_and_slow_queries()

  if thresholds_failed:
    create_bottleneck_report()
    block_release_until_fixed()
  else:
    record_capacity_baseline()
```

## Bizim Notlarımız / Takım Kararları

- 08:30 yoklama piki high-priority load scenario'dur.
- k6 scenario gerçek kullanıcı akışını, read ve write adımlarını modellemelidir.
- Threshold açık olmalıdır; test objektif olarak pass/fail üretmelidir.
- Darboğaz analizi [Observability: Log, Metric, Trace ve Correlation ID](./observability-logs-metrics-traces-correlation-id) kurallarına bağlanmalıdır.

## Harici Referanslar

- Grafana k6: [Documentation](https://grafana.com/docs/k6/latest/)

## Sözlük

- Virtual user: yük testindeki simüle kullanıcı.
- Scenario: k6 tarafından çalıştırılan traffic modeli.
- Threshold: metric bazlı pass/fail kuralı.
- Ramping arrival rate: request arrival rate'i zamanla değiştiren executor.
- Bottleneck: sistemin kapasitesini sınırlayan component.
- Saturation: resource'un aşırı yüklenmiş durumu.

## Araştırma Keywordleri

- `k6 load testing thresholds scenarios`
- `ramping arrival rate bottleneck analysis`
- `k6 attendance peak load test`
- `load testing database connection pool`
- `k6 p95 p99 latency thresholds`

## Sonuç

Yük testi gerçek business peak'i modellediğinde değerlidir. 08:30 attendance scenario; metric, trace ve bottleneck analiziyle bağlı repeatable bir release confidence test'i olmalıdır.
