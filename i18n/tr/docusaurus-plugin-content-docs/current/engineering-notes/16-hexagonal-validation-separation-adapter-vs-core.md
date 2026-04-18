---
title: Hexagonal Validation Ayrımı (Adapter vs Core)
description: "Hexagonal Architecture'da validation sorumluluğu: adapter seviyesinde input validation, core seviyesinde business invariant kontrolü."
sidebar_position: 3
---

## Giriş

Backend tarafında en sık yapılan mimari hatalardan biri tüm validation kurallarını aynı katmana yığmaktır. Bu durum pratikte REST, Kafka ve batch akışlarında tekrar eden ve farklılaşan kontrol setleri üretir.

Bu doküman, Hexagonal Architecture içinde validation sorumluluklarının doğru ayrımını açıklar.

## Neden Önemli

Validation sınırları net olmazsa:

- business logic controller ve consumer'lara sızar,
- aynı kural birden fazla yerde yazılır,
- entry point'e göre davranış farklılaşır,
- domain tutarlılığı kırılgan hale gelir.

Doğru ayrım, kural dağılmasını önler ve tüm adapter'larda aynı davranışı garanti eder.

## Temel Kavramlar

- Input validation: dış girdinin format/sözleşme doğruluğu kontrolü.
- Business invariant: domain içinde her zaman doğru kalması gereken iş kuralı.
- Adapter: dış giriş noktası (`REST`, `Kafka`, `Batch`).
- Core: domain + application use-case + port katmanları.
- Defensive design: adapter doğrulasa bile kritik kuralları core'da tekrar doğrulama.

## Gerçek Ürün Geliştirmede Problem

Sık görülen anti-pattern: her adapter'ın kendi kural setini yazması.

- REST controller bir set kontrol yapar,
- Kafka consumer farklı set uygular,
- servis katmanında üçüncü bir varyasyon oluşur.

Bu yaklaşım tutarsız sonuç ve production bug riski üretir.

## Yaklaşım / Tasarım

### Sorumluluk Ayrımı

- Adapter sorumluluğu:
  - "Bu payload sistem sınırından içeri alınabilir mi?"
  - nullability, schema, format, enum/range, parse kontrolü

- Core sorumluluğu:
  - "Bu işlem iş kuralları açısından geçerli mi?"
  - status, sahiplik, yaşam döngüsü gibi invariant kontrolleri

### Akış

```text
REST/Kafka/Batch Adapter
  -> input validation
  -> command mapping
  -> core use case
  -> invariant checks
  -> side effects and persistence
```

### Örnek (REST + Core)

```java
@PostMapping("/orders")
public ResponseEntity<Void> create(@Valid @RequestBody CreateOrderRequest request) {
  CreateOrderCommand command = mapper.toCommand(request);
  createOrderUseCase.handle(command);
  return ResponseEntity.accepted().build();
}

public void handle(CreateOrderCommand command) {
  Customer customer = customerRepository.findById(command.customerId());
  if (!customer.isActive()) {
    throw new BusinessException("Pasif müşteri sipariş veremez");
  }
}
```

## Sektör Standardı / Best Practice

- Transport seviyesindeki validation adapter'da tutulmalı.
- Domain invariant kontrolleri core use-case/aggregate içinde tutulmalı.
- Tüm adapter'lar aynı core use-case yolunu çağırmalı.
- Adapter'a özel business rule dallanmalarından kaçınılmalı.
- Hangi kuralın hangi katmana ait olduğu dokümante edilmeli.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
process_request(input, adapter_type):
  if not adapter_contract_valid(input):
    reject(reason='input_invalid')

  command = map_to_command(input)

  if not core_business_invariants_pass(command):
    reject(reason='business_invariant_failed')

  execute_use_case(command)
  return success
```

## Bizim Notlarımız / Takım Kararları

- `Kafka`, `REST` gibi adapter kabul edilir ve aynı prensiple değerlendirilir.
- Core sadece "service" katmanı değildir; domain + application + port içerir.
- Validation açık biçimde adapter-level ve core-level olarak ayrılmalıdır.
- Kritik invariant'lar core'da son savunma hattı olarak kalmalıdır.

## Sözlük

- Input validation: sistem sınırındaki yapısal/sözleşme doğrulaması.
- Business invariant: domain bütünlüğünü koruyan zorunlu iş kuralı.
- Adapter: dış dünyadan veri alan sınır bileşeni.
- Core: taşıma/protokolden bağımsız iş mantığı merkezi.
- Command: use-case çalıştırmak için normalize edilen giriş nesnesi.

## Araştırma Keywordleri

- `input validation vs business validation`
- `hexagonal architecture validation separation`
- `domain invariant examples`
- `where to validate in clean architecture`
- `adapter vs domain validation`

## Sonuç

Validation kalitesi daha fazla kontrol eklemekten çok, kontrolün doğru sınırda yapılmasıyla ilgilidir. Adapter input sözleşmesini korur, core ise iş gerçeğini korur.
