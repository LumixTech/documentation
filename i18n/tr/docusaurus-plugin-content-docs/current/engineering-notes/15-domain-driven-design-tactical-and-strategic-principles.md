---
title: Domain-Driven Design Taktiksel ve Stratejik Tasarım İlkeleri
description: Entity/value object ayrımı, aggregate invariant yönetimi, bounded context sınırları ve domain event yaklaşımı için pratik DDD rehberi.
sidebar_position: 2
---

## Giriş

Ürün büyüdükçe iş kuralları controller, servis ve SQL script'lere dağılabilir. Domain-Driven Design (DDD), karmaşıklığı teknik katmanlara göre değil iş anlamına göre modellemeyi hedefler.

Bu sayfa, taktiksel ve stratejik DDD prensiplerini mühendislik karar rehberi olarak özetler.

## Neden Önemli

Domain modeli net olmazsa:

- kritik iş kuralları ad hoc güncellemelerle bypass edilir,
- ekipler aynı kavram için farklı dil kullanır,
- modüller arası veri tutarlılığı bozulur,
- davranış kodu dağıldığı için test edilebilirlik düşer.

DDD, iş kurallarını doğru sınırlar içine yerleştirerek bu riski azaltır.

## Temel Kavramlar

- Entity: durum değişse de kimliği sabit kalan varlık.
- Value object: kimliği olmayan, immutable değer nesnesi.
- Aggregate: birlikte tutarlılık koruyan domain nesneleri sınırı.
- Aggregate root: aggregate dışına açılan tek kapı.
- Invariant: aggregate içinde her zaman doğru kalması gereken kural.
- Bounded context: modelin ve dilin geçerli olduğu sınır.
- Domain event: diğer modüllere yayılan anlamlı iş olayı.

## Gerçek Ürün Geliştirmede Problem

CRUD merkezli model, karmaşık iş davranışlarında yetersiz kalır:

- siparişte geçmiş adresin, canlı müşteri adresine bağlı olduğu için yanlışlıkla değişmesi,
- sınıf/öğrenci yönetiminde kapasite üstü kayıt yapılması,
- servis katmanındaki büyüyen `if-else` blokları içinde kural kaybı.

Bu sorunlar sadece kod kalitesi değil, sınır ve modelleme sorunudur.

## Yaklaşım / Tasarım

### Taktiksel Tasarım Kuralları

- Invariant kontrollerini aggregate root metotlarında tut.
- Serbest alan güncellemesi yerine davranış metotlarıyla değişim yap.
- Snapshot gereken verilerde immutable value object yaklaşımını kullan.
- Sadece veri taşıyan anemic domain modelden kaçın.

### Stratejik Tasarım Kuralları

- Bounded context sınırlarını ve dil sahipliğini açık tanımla.
- Context'ler arası upstream/downstream kontratlarını netleştir.
- Context'ler arası kimlikte stabil public ID (ör. UUID) kullan.
- Context'ler arası etki için doğrudan bağımlılık yerine domain event kullan.

### Snapshot Karar Deseni

Geçmişi temsil eden verilerde (ör. sipariş anı adresi), canlı referans yerine o anki değeri snapshot olarak kopyala.

## Sektör Standardı / Best Practice

- Tutarlılığı doğru yerde uygula: aggregate içinde.
- Mümkün olduğunda transaction sınırını tek aggregate tutarlılık sınırıyla hizala.
- Application service katmanını orkestrasyon için kullan; domain kurallarını burada biriktirme.
- Context'ler arası yayılımda domain event yaklaşımını tercih et.
- Ürün ve mühendislik ekibi arasında ubiquitous language birliğini koru.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
place_order(customer_id, cart, shipping_address_input):
  order = order_repository.load_draft(customer_id)

  shipping_address = Address.from(shipping_address_input)  # value object
  order.set_shipping_address_snapshot(shipping_address)

  for item in cart.items:
    order.add_item(item.product_id, item.quantity)

  order.confirm()  # aggregate root invariant kontrolü

  unit_of_work.commit(order)
  publish(DomainEvent('OrderConfirmed', order.id))
```

## Bizim Notlarımız / Takım Kararları

- Yüksek riskli domainlerde kural sahipliğini aggregate merkezli kurmayı tercih ediyoruz.
- Geçmiş doğruluğu kritik verilerde (ör. sipariş anı adres) snapshot zorunlu olmalı.
- Context'ler arası kimlik aktarımında lokal DB PK varsayımı yerine global stabil ID kullanılmalı.
- Unit of Work ve transaction sınırı, önce domain tutarlılığını garanti etmeli.

## Sözlük

- Entity: kimliği olan ve yaşam döngüsü taşıyan domain nesnesi.
- Value object: değer eşitliğiyle tanımlanan immutable nesne.
- Aggregate root: aggregate durum geçişlerinin tek giriş noktası.
- Invariant: her zaman doğru kalması gereken iş kuralı.
- Bounded context: model anlamının sınırı.
- Unit of Work: ilişkili değişikliklerin transaction koordinasyonu.

## Araştırma Keywordleri

- `domain driven design tactical patterns`
- `entity vs value object ddd`
- `aggregate root invariants`
- `bounded context strategic design`
- `domain event consistency boundaries`

## Sonuç

DDD, iş karmaşıklığının yüksek ve tutarlılığın kritik olduğu alanlarda en yüksek faydayı sağlar. Taktiksel desenler invariant'ları güvence altına alır, stratejik sınırlar ise sistem büyürken ekip hizasını korur.
