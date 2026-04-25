---
title: Event-Driven Mimari Sözlüğü
description: DDD odaklı event modelleme, integration event, mesaj kontratı ve asenkron iletişim için ortak terimler.
sidebar_position: 1
---

## Giriş

Bu sözlük, Event-Driven Architecture dokümanlarında kullanılan ortak teknik dili sabitler. Amaç, domain içi anlam ile dış dünyaya açılan mesaj kontratını birbirinden ayırmaktır.

## Glossary

### Domain Event

Bounded context içinde gerçekleşmiş, iş açısından anlamlı domain gerçeği.

### Integration Event

Dış sistemlere yayımlanan, stabil ve geriye uyumlu event kontratı.

### Command

Bir sistemden belirli bir davranış gerçekleştirmesini isteyen mesaj.

### Event

Geçmişte gerçekleşmiş bir iş olgusunu bildiren mesaj.

### Entity Leakage

Persistence/domain entity'sinin event payload'u içinde dış sistemlere sızması.

### Semantic Coupling

Consumer servislerin producer servisin iç model anlamlarına, state isimlerine veya yaşam döngüsü varsayımlarına bağımlı hale gelmesi.

### Event Contract

Dış sistemlerin güvenerek tükettiği event şeması ve anlam sözleşmesi.

### Payload Bloat

Event'in her consumer'ın ihtiyacını karşılamaya çalışırken gereksiz büyümesi.

### Idempotency

Aynı event birden fazla işlense bile sistemin tutarlı sonuç üretmesi.

### Backward Compatibility

Event şeması değişse bile eski consumer'ların kırılmadan çalışmaya devam etmesi.

## Araştırma Keywordleri

- `domain event ddd`
- `integration event contract`
- `event contract versioning`
- `event driven architecture coupling`
- `command vs event`

## Sonuç

Net terminoloji, kazara coupling üretmeyi engeller. Domain event, integration event ve command aynı araç değildir; ayrı isimlendirilmeli ve ayrı modellenmelidir.
