---
title: "Command ve Event Ayrımı: Emir Vermek mi, Gerçek Bildirmek mi?"
description: Command ve event mesajlarının servis coupling'i, orchestration, choreography ve asenkron otonomi üzerindeki etkilerini açıklar.
sidebar_position: 5
---

## Giriş

Event-driven sistemlerde command/event ayrımı kritik bir modelleme kararıdır. Yanlış seçim, servisler arası coupling'i sessizce artırabilir.

Command şunu söyler: "Bunu yap."

Event şunu söyler: "Bu oldu."

## Command Communication

Command hedef sistemden davranış ister:

```text
Order Service -> Billing Service:
  CapturePayment(orderId)
```

Bu, bir bileşenin süreci bilinçli şekilde koordine ettiği orchestration akışlarında doğru olabilir. Fakat sender, kimin ne yapması gerektiğini bilir.

Bu daha güçlü coupling üretir.

## Event Communication

Event tamamlanmış iş gerçeğini duyurur:

```text
Order Service publishes:
  OrderPlacedIntegrationEvent
```

Billing, Notification, Analytics, Warehouse ve CRM servisleri bu olaya kendi kararlarıyla tepki verebilir.

```text
OrderPlacedIntegrationEvent
  -> Billing ödeme akışını başlatıp başlatmayacağına karar verir
  -> Notification müşteriye bildirim gönderip göndermeyeceğine karar verir
  -> Analytics raporu günceller
  -> Warehouse fulfillment hazırlığı yapar
```

Producer tüm consumer'ları bilmek zorunda değildir.

## Orchestration vs Choreography

Orchestration:

- tek coordinator süreci yönetir,
- command'lar genellikle explicit'tir,
- akışı merkezi olarak incelemek daha kolaydır,
- participant davranışlarına coupling daha yüksektir.

Choreography:

- servisler event'lere tepki verir,
- tüm adımları tek servis yönetmez,
- autonomy daha yüksektir,
- iyi observability yoksa akışı takip etmek zorlaşabilir.

İki model de faydalıdır. Hata, iş anlamı sadece "bu oldu" iken command kullanmaktır.

## OrderPlaced Örneği

Geniş iş olgusu için zayıf model:

```text
Order Service sends commands:
  CreateInvoice
  SendOrderEmail
  UpdateAnalytics
  ReserveWarehouseStock
```

Event-driven collaboration için daha iyi model:

```text
Order Service publishes:
  OrderPlacedIntegrationEvent
```

Consumer servisler kendi reaksiyonlarının ownership'ini alır.

## Command Ne Zaman Mantıklı?

Command şu durumlarda uygun olabilir:

- açık bir hedef owner varsa,
- sender bilinçli olarak davranış talep ediyorsa,
- workflow orchestrated ise,
- success/failure bir process manager veya saga tarafından koordine edilecekse,
- receiver explicit command API sağlıyorsa.

## Event Ne Zaman Mantıklı?

Event genellikle şu durumlarda daha uygundur:

- producer sadece iş gerçeğinin gerçekleştiğini biliyorsa,
- birden fazla consumer bağımsız tepki verebiliyorsa,
- consumer autonomy korunmak isteniyorsa,
- producer downstream workflow'ları bilmemeliyse,
- sistem merkezi kontrol yerine choreography tercih ediyorsa.

## Teknik Terimler Sözlüğü

- Command
- Event
- Orchestration
- Choreography
- Producer
- Consumer
- Service Coupling
- Autonomy
- Asynchronous Communication

## Bizim Notlarımız / Özel Vurgular

- "Command üretip servislere dağıtmak" yaklaşımı dikkatli ele alınmalıdır.
- Bu model bazı orchestration senaryolarında geçerli olabilir.
- `OrderPlaced` gibi geniş iş olgularında daha doğru default genellikle Integration Event yayımlamaktır.

## Araştırma Keywordleri

- `command vs event`
- `event choreography vs orchestration`
- `service coupling event driven architecture`
- `asynchronous communication patterns`
- `spring cloud stream kafka event command`

## Sonuç

Dış servisleri bilinçli olarak yönetmek istiyorsan command kullanırsın, ama coupling artar. Olan bir iş gerçeğini duyurmak istiyorsan integration event yayımlarsın. EDA'da tercih edilen model çoğu zaman emir dağıtmak değil, olayı ilan etmektir.
