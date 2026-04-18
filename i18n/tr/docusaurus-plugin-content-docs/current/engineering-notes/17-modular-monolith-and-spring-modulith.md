---
title: Modüler Monolit Mimarisi ve Spring Modulith
description: Spring Modulith, DDD sınırları ve hexagonal katmanlama ile modüler monolit tasarım rehberi.
sidebar_position: 4
---

## Giriş

Monolitlerin asıl problemi monolit olmaları değil, sınırlarının belirsiz olmasıdır. Ekipler çoğu zaman bu problemi erken mikroservise geçerek çözmeye çalışır ve domain coupling çözülmeden dağıtık karmaşıklık üretir.

Bu doküman, DDD ve hexagonal tasarım ile hizalı modüler monolit disiplinini Spring Modulith üzerinden açıklar.

## Neden Önemli

Modül sınırları net değilse:

- bağımlılıklar örtük ve döngüsel hale gelir,
- veri sahipliği belirsizleşir,
- değişikliklerin blast radius etkisi büyür,
- mikroservise geçiş pahalı ve riskli olur.

Modüler monolit, deploy sadeliğini korurken iç ayrışmayı enforce eder.

## Temel Kavramlar

- Modüler monolit: tek deployable içinde sınırları belirli modüller.
- Spring Modulith: modül sınırlarını tanımlama ve illegal bağımlılıkları tespit etme araçları.
- Bounded context: kendi model ve diline sahip iş kabiliyeti sınırı.
- API-first modül kontratı: modüller arası erişimin yalnızca public API paketiyle yapılması.
- Event-driven modül iletişimi: modüller arası gevşek bağlı etkileşim modeli.

## Gerçek Ürün Geliştirmede Problem

Sık görülen monolit bozulma paterni:

- bir modülün diğer modül repository'sine doğrudan erişmesi,
- iç entity'lerin farklı modüllerde yeniden kullanılması,
- modül sahipliğini bypass eden cross-module join'ler.

Bu durum zamanla iş kurallarını ortak ve kontrolsüz mutable mantığa dönüştürür.

## Yaklaşım / Tasarım

### Modül Sınır Şablonu

```text
com.company.academic
  - api
  - internal.application
  - internal.domain
  - internal.infrastructure
```

Aynı yapı `finance`, `core-iam`, `communication` gibi modüller için tekrarlanır.

### Katman Sorumluluğu

- `api`: diğer modüllere açılan public kontrat.
- `internal.application`: use-case orkestrasyonu ve transaction sınırı.
- `internal.domain`: entity, aggregate, value object, domain kuralları.
- `internal.infrastructure`: DB adapter, messaging adapter, external client entegrasyonları.

### Modüller Arası İletişim Kuralı

- başka modülün `internal.*` alanına doğrudan erişim yok,
- modüller arası repository/entity paylaşımı yok,
- iletişim module API ve domain event üzerinden yapılır.

## Sektör Standardı / Best Practice

- Mimari sınırları sadece konvansiyonla değil test ile enforce et.
- Veri sahipliğini açık tut: her tablonun tek bir owning modülü olmalı.
- Modüller arası düşük coupling için event-driven entegrasyonu tercih et.
- Modül API'lerini stabil tut ve gerekiyorsa versiyonla.
- Önce modüler monolit disiplini kur, mikroservis ayrışmasını ihtiyaç doğduğunda yap.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
on FEATURE_REQUEST(capability):
  owner_module = map_capability_to_module(capability)

  if request_touches_other_module_internal(owner_module):
    reject_and_refactor_to_api_or_event()

  implement_use_case_in(owner_module.internal.application)
  enforce_rules_in(owner_module.internal.domain)

  if cross_module_effect_exists:
    publish_domain_event()
```

## Bizim Notlarımız / Takım Kararları

- Tercih edilen kombinasyon: Spring Modulith + DDD + Hexagonal.
- Başlangıç modül sınırları: `core-iam`, `academic`, `finance`, `communication`.
- Encapsulation zorunlu: dışa sadece `api` açılır.
- Modüller arası side-effect için event-driven entegrasyon tercih edilir.
- Modüller arası repository/entity paylaşımı açıkça reddedilir.

## Sözlük

- Modüler monolit: tek deployable içinde modüler ayrışma yaklaşımı.
- Spring Modulith: Spring uygulamalarında modül sınırı doğrulama yaklaşımı.
- Encapsulation: iç implementasyonun diğer modüllerden gizlenmesi.
- Blast radius: bir değişikliğin etki alanı.
- Domain event: modüller arası entegrasyonda kullanılan iş olayı.

## Araştırma Keywordleri

- `spring modulith architecture patterns`
- `modular monolith vs microservices`
- `ddd bounded context in monolith`
- `hexagonal architecture in modular monolith`
- `event driven module integration`

## Sonuç

Mimari kazanım "önce mikroservis" değil "önce sınır" yaklaşımıyla gelir. Spring Modulith ile kurulan modüler monolit, güçlü ayrışma, yönetilebilir operasyon ve gelecekte güvenli servisleşme zemini sağlar.
