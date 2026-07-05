---
title: "Test Piramidi: Unit, Integration, Contract ve E2E Stratejisi"
description: Product use case'lerini unit, integration, contract ve end-to-end test seviyelerine dağıtma stratejisi.
sidebar_position: 4
---

## Giriş

Test piramidi; confidence, hız, maliyet ve maintainability dengesini farklı test seviyeleri arasında kurma stratejisidir.

Her davranış E2E test ile doğrulanmamalıdır. E2E test değerlidir; fakat daha yavaş, daha kırılgan ve hata teşhisi daha zordur. Sağlıklı test stratejisi, test edilen risk için doğru seviyeyi seçer.

## Neden Önemli

Yanlış test dağılımı iki problem üretir:

- çok fazla low-level test integration failure'ları kaçırır,
- çok fazla E2E test CI'ı yavaş ve flaky hale getirir.

Amaç maksimum test sayısı değil; hızlı ve anlamlı güvendir.

## Temel Kavramlar

- Unit test: küçük logic'i izolasyonda doğrular.
- Integration test: gerçek infrastructure veya framework davranışıyla collaboration doğrular.
- Contract test: producer ve consumer arasındaki API/event beklentisini doğrular.
- E2E test: UI veya external boundary üzerinden tam user workflow doğrular.
- Test pyramid: daha çok low-level, daha az high-level test prensibi.
- Test slice: tek layer veya module için focused test scope.
- Happy path: beklenen başarılı workflow.
- Sad path: failure veya validation workflow.

## Gerçek Ürün Geliştirmede Problem

Ekipler bazen görünürlüğe göre test eder:

```text
Kullanıcı görüyorsa browser'da test et
```

Bu maliyetlidir. Örneğin:

- authorization matrix logic unit ve integration test ile daha iyi kapsanır,
- database constraint integration/migration test ister,
- API compatibility contract test ister,
- role-based UI navigation E2E test ister.

Her use case, ilgili riski yakalayan en ucuz test seviyesine yerleştirilmelidir.

## Yaklaşım / Tasarım

### Use Case Dağılımı

| Use case | Unit | Integration | Contract | E2E |
| --- | --- | --- | --- | --- |
| Login token validation | token policy, expiration rules | Spring Security filter ve DB/session state | auth API response shape | login happy/sad path |
| Attendance marking | attendance rules | repository, transaction, outbox | attendance API/event contract | teacher marks attendance |
| Chat message send | validation ve permission rules | persistence ve websocket/event integration | message event schema | teacher/parent sees message |
| Payment attempt | fee calculation rules | provider adapter test double, audit log | provider callback contract | admin/payment workflow smoke |
| Permission update | allow/deny precedence | authorization service DB state | admin API contract | admin role UI flow |
| DSAR workflow | state transition rules | anonymization ve audit integration | DSAR API contract | admin approval flow |

### Test Seviyesi Rehberi

Unit test kullan:

- logic deterministikse,
- framework/infrastructure gerekmezse,
- edge case sayısı fazlaysa.

Integration test kullan:

- database davranışı önemliyse,
- transaction önemliyse,
- Spring security/module wiring önemliyse,
- migration ve repository doğrulanacaksa.

Contract test kullan:

- başka frontend/service API tüketiyorsa,
- event schema backward-compatible kalmalıysa,
- provider callback shape katıysa.

E2E test kullan:

- role-based UI davranışı önemliyse,
- browser davranışı önemliyse,
- birçok layer birlikte kanıtlanmalıysa.

## Sektör Standardı / Best Practice

- Unit testleri hızlı ve çok tut.
- Real persistence ve framework boundary için integration test kullan.
- API/event compatibility için contract test yaz.
- E2E testleri kritik workflow'larla sınırlı tut.
- High-risk use case'lerde happy path ve sad path test et.
- Aynı assertion'ı her seviyede tekrar etme.
- CI stage'lerini test maliyeti ve confidence'a göre ayır.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
choose_test_level(use_case, risk):
  if risk pure business rule ise:
    return unit_test

  if risk database/framework/module wiring gerektiriyorsa:
    return integration_test

  if risk producer-consumer compatibility ise:
    return contract_test

  if risk UI ve backend boyunca user workflow ise:
    return e2e_test

  return integration_test
```

## Bizim Notlarımız / Takım Kararları

- Project use case'leri E2E'ye yığılmadan test seviyelerine dağıtılmalıdır.
- Kritik flow'larda happy path ve sad path coverage gerekir.
- Authorization ve tenant isolation mümkün olduğunca UI altında test edilmelidir.
- E2E testler admin, öğretmen ve veli akışlarını [Playwright E2E, RBAC UI Testleri ve Form Akışları](../frontend-architecture/playwright-e2e-rbac-ui-form-flows) sayfasındaki gibi kapsamalıdır.

## Harici Referanslar

- Martin Fowler: [The Practical Test Pyramid](https://martinfowler.com/articles/practical-test-pyramid.html)

## Sözlük

- Unit test: küçük logic için izole test.
- Integration test: gerçek framework veya infrastructure boundary içeren test.
- Contract test: producer-consumer compatibility testi.
- E2E test: kullanıcı boundary'sinden tam workflow testi.
- Test pyramid: farklı test granularities için dağılım stratejisi.

## Araştırma Keywordleri

- `test pyramid unit integration contract e2e`
- `testing strategy spring boot frontend e2e`
- `contract testing api event schema`
- `test levels product use cases`
- `happy path sad path testing strategy`

## Sonuç

İyi test piramidi bir karar framework'üdür. Ürün risklerini doğru test seviyesine koyarak CI'ı hızlı, hataları teşhis edilebilir ve kritik workflow'ları korunaklı tutar.
