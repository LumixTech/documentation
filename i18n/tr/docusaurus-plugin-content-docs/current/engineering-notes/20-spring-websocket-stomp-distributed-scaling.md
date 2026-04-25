---
title: "Spring WebSocket & STOMP Mimarisi ve Dağıtık Sistemlerde Ölçekleme"
description: Spring WebSocket, STOMP, in-memory broker sınırları, broker relay, RabbitMQ, user-specific messaging ve dağıtık ölçekleme için mühendislik notu.
sidebar_position: 7
---

## Giriş

WebSocket mimarisi, klasik HTTP request-response modelinden farklı olarak uzun ömürlü, çift yönlü ve stateful bağlantılar üzerine kuruludur.

HTTP dünyasında server çoğu zaman isteği işler, cevabı döner ve bağlantı yaşam döngüsünü sonlandırır. WebSocket dünyasında ise server, client ile canlı bir bağlantı tutar. Bu bağlantı üzerinden hem client server'a mesaj gönderebilir hem de server client istemeden client'a mesaj push edebilir.

Bu özellik chat, notification, canlı dashboard, trading ekranı, multiplayer oyun ve collaborative editing gibi senaryolarda güçlüdür. Ancak dağıtık sistemlerde önemli bir bedeli vardır:

> WebSocket bağlantısı sadece network connection değildir; dağıtık sistem state'idir.

Bu nedenle tek pod'da çalışan bir WebSocket sistemi production ortamında çoklu pod, autoscaling, pod crash, reconnect, user presence ve message fan-out problemleriyle karşılaşır.

## Temel Kavramlar

### HTTP Request-Response

HTTP'de client bir istek gönderir, server cevap döner ve işlem tamamlanır.

```text
Client  --->  HTTP Request   --->  Server
Client  <---  HTTP Response  <---  Server
```

Bu model genellikle stateless tasarıma uygundur. Load balancer gelen istekleri farklı pod'lara dağıtabilir.

```text
Request 1 -> Pod A
Request 2 -> Pod B
Request 3 -> Pod C
```

### WebSocket Full-Duplex Connection

WebSocket bağlantısı uzun ömürlüdür ve çift yönlüdür.

```text
Client  <====================>  Server
          Full-duplex channel
```

Bu bağlantı kurulduktan sonra aynı TCP/WebSocket connection üzerinden mesajlaşma devam eder.

### Connection Ownership

WebSocket bağlantısı hangi pod'a açıldıysa o pod tarafından sahiplenilir.

```text
Client A -> Pod-1
Client B -> Pod-2
Client C -> Pod-3
```

Client A'nın canlı socket bağlantısı Pod-1 üzerindedir. Pod-2, Client A'nın socket'ine doğrudan yazamaz.

### STOMP

STOMP, WebSocket üzerinde çalışan mesajlaşma protokolüdür.

Örnek frame tipleri:

```text
CONNECT
SUBSCRIBE
SEND
MESSAGE
DISCONNECT
```

WebSocket düşük seviyeli bağlantıyı sağlar. STOMP ise bu bağlantı üzerinde destination, subscription ve message semantics sağlar.

### In-Memory Broker

Spring'de şu yapı simple broker açar:

```java
registry.enableSimpleBroker("/topic", "/queue");
```

Bu broker uygulama pod'unun JVM memory'sinde çalışır. Subscription registry, session bilgileri ve routing bilgileri local tutulur.

### Broker Relay

Spring'de şu yapı mesajları harici broker'a relay eder:

```java
registry.enableStompBrokerRelay("/topic", "/queue");
```

Bu modelde Spring gerçek broker gibi davranmaz; RabbitMQ veya ActiveMQ gibi harici broker'a STOMP mesajlarını iletir.

## Problem

Tek pod'da WebSocket sistemi basit görünür:

```text
Client A -> Pod-1
Client B -> Pod-1
Client C -> Pod-1
```

Tüm client'lar aynı pod'a bağlı olduğu için local in-memory broker tüm subscription bilgilerini bilir.

Ancak çoklu pod'a geçildiğinde state parçalanır:

```text
Client A -> Pod-1
Client B -> Pod-2
Client C -> Pod-3
```

Her pod sadece kendi memory'sindeki WebSocket session'larını ve subscription'larını bilir.

Örneğin üç client da `/topic/orders` destination'ına subscribe olmuş olsun:

```text
Client A -> Pod-1 -> /topic/orders
Client B -> Pod-2 -> /topic/orders
Client C -> Pod-3 -> /topic/orders
```

Pod-1 içinde şu kod çalışırsa:

```java
simpMessagingTemplate.convertAndSend("/topic/orders", orderCreatedEvent);
```

`enableSimpleBroker("/topic")` kullanılıyorsa mesaj sadece Pod-1'in local broker'ına gider.

Sonuç:

```text
Client A -> mesajı alır
Client B -> mesajı almaz
Client C -> mesajı almaz
```

Çünkü Client B'nin subscription bilgisi Pod-2'nin memory'sindedir. Client C'ninki Pod-3'ün memory'sindedir.

Buradaki temel problem:

> Local truth, global truth değildir.

Bir pod'un bildiği state, sistemin tamamını temsil etmez.

## Yanlış Yaklaşımlar

### Sticky Session Yeterli Sanmak

Sticky session, bir kullanıcının aynı pod'a yönlendirilmesini sağlar.

```text
User-123 -> hep Pod-1
```

Ancak sticky session şu problemleri çözmez:

```text
1. Başka pod'da oluşan event'in User-123'e nasıl ulaşacağını çözmez.
2. Pod ölürse connection state yine kaybolur.
3. Global subscription bilgisini oluşturmaz.
4. Broadcast problemini çözmez.
5. Autoscaling sırasında podlar arası koordinasyon sağlamaz.
```

Sticky session connection ownership'i stabilize eder ama distributed messaging problemini çözmez.

### Pod-to-Pod HTTP Broadcast

Bir diğer yaklaşım, event oluşan pod'un diğer pod'lara HTTP çağrısı yapmasıdır.

```text
Pod-2 -> Pod-1'e HTTP çağrısı
Pod-2 -> Pod-3'e HTTP çağrısı
```

Bu yaklaşım kırılgandır çünkü Pod-2 şunları bilmek zorunda kalır:

```text
Şu anda kaç pod var?
Hangi pod ayakta?
Yeni pod eklendi mi?
Bir pod timeout olduysa retry yapılacak mı?
Duplicate mesaj nasıl engellenecek?
```

Bu durum application pod'larını gereksiz yere cluster coordination sorumluluğuna sokar.

## Doğru Yaklaşım: Broker / Pub-Sub / Broker Relay

Daha sağlıklı yaklaşım, pod'ların birbirini doğrudan bilmemesidir.

```text
Pod-1 ----\
Pod-2 -----+---- RabbitMQ / ActiveMQ
Pod-3 ----/
```

Pod-2'de bir event oluştuğunda event ortak broker'a publish edilir. Diğer pod'lar broker üzerinden ilgili mesajı alır ve kendi local WebSocket session'larına iletir.

```text
Backend event on Pod-2
        |
        v
Pod-2 publishes event to broker
        |
        v
Broker distributes event
        |
        +--> Pod-1 sends to local clients
        +--> Pod-2 sends to local clients
        +--> Pod-3 sends to local clients
```

Burada önemli ayrım:

> Broker global message routing sağlar.
> Spring pod hâlâ local WebSocket connection owner'dır.

Yani RabbitMQ, Client B'nin socket'ine doğrudan yazmaz. RabbitMQ mesajı Pod-2'ye ulaştırır. Pod-2 de kendi üzerindeki WebSocket connection'a yazar.

## Spring STOMP Akışı

Tipik Spring konfigürasyonu:

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }
}
```

Burada prefix ayrımı önemlidir:

```text
/app    -> Spring application method'larına gider
/topic  -> broker üzerinden broadcast mesajları
/queue  -> genellikle user-specific mesajlar
```

Örnek client akışı:

```text
Client SEND /app/orders/create
        |
        v
Spring @MessageMapping
        |
        v
Application logic
        |
        v
Broker sends MESSAGE to /topic/orders
        |
        v
Subscribed clients receive message
```

Örnek backend kodu:

```java
@MessageMapping("/orders/create")
public void createOrder(CreateOrderCommand command) {
    OrderCreatedEvent event = orderService.create(command);
    messagingTemplate.convertAndSend("/topic/orders", event);
}
```

## In-Memory Broker Sınırı

`enableSimpleBroker` kullanıldığında broker state'i local JVM içindedir.

Her pod kendi local broker'ına sahiptir:

```text
Pod-1:
- Client A session
- Client A subscriptions

Pod-2:
- Client B session
- Client B subscriptions

Pod-3:
- Client C session
- Client C subscriptions
```

Bu nedenle Pod-1'de üretilen mesaj sadece Pod-1'in bildiği subscriber'lara ulaşır.

Bu durum production'da şu semptomlarla ortaya çıkar:

```text
Bazı kullanıcılar notification alır, bazıları almaz.
Refresh atınca bazen düzelir.
Admin panelde canlı veri eksik görünür.
Mesajlar rastgele kayboluyor gibi görünür.
```

Aslında problem rastgele değildir. Mesaj hangi pod'da üretildiyse, sadece o pod'un local broker state'ine göre dağıtılır.

## Broker Relay ile Dağıtık Mesajlaşma

Broker relay kullanıldığında Spring, mesajları harici broker'a iletir.

```java
@Override
public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableStompBrokerRelay("/topic", "/queue")
            .setRelayHost("rabbitmq")
            .setRelayPort(61613)
            .setClientLogin("guest")
            .setClientPasscode("guest")
            .setSystemLogin("guest")
            .setSystemPasscode("guest");

    registry.setApplicationDestinationPrefixes("/app");
}
```

Bu durumda akış şöyle olur:

```text
Client A -> Pod-1
Client B -> Pod-2
Client C -> Pod-3

A, B, C subscribe /topic/orders

Pod-1:
simpMessagingTemplate.convertAndSend("/topic/orders", payload)
        |
        v
RabbitMQ
        |
        +--> Pod-1 -> Client A
        +--> Pod-2 -> Client B
        +--> Pod-3 -> Client C
```

RabbitMQ burada global routing ve fan-out sorumluluğunu üstlenir.

Spring pod'larının sorumluluğu ise şudur:

```text
WebSocket endpoint sağlamak
Authentication yapmak
Authorization yapmak
Principal mapping tutmak
Domain logic çalıştırmak
Local socket'lere mesaj yazmak
```

RabbitMQ'nun sorumluluğu:

```text
Message routing
Fan-out
Cross-pod message distribution
Broker-level delivery
```

## RabbitMQ STOMP vs Web-STOMP

RabbitMQ tarafında iki kavram karıştırılmamalıdır.

### RabbitMQ STOMP Plugin

Spring pod'larının RabbitMQ'ya STOMP protokolüyle bağlanmasını sağlar.

```text
Spring Pod -> RabbitMQ STOMP Port
```

### RabbitMQ Web-STOMP Plugin

Browser client'ların doğrudan RabbitMQ'ya WebSocket üzerinden STOMP ile bağlanmasını sağlar.

```text
Browser -> RabbitMQ Web-STOMP
```

Ancak enterprise Spring mimarisinde çoğu zaman browser doğrudan RabbitMQ'ya bağlanmaz.

Tercih edilen model genellikle:

```text
Browser -> Spring WebSocket Endpoint -> RabbitMQ
```

Çünkü Spring arada şu görevleri üstlenir:

```text
Authentication
Authorization
Domain validation
Principal mapping
Security boundary
Application command handling
```

Browser'ı doğrudan broker'a bağlamak güvenlik yüzeyini büyütebilir.

## User-Specific Messaging Problemi

Spring'de kullanıcıya özel mesaj göndermek için şu yapı kullanılır:

```java
simpMessagingTemplate.convertAndSendToUser(
    "userB",
    "/queue/notifications",
    payload
);
```

Client tarafında:

```javascript
stompClient.subscribe("/user/queue/notifications", handler);
```

Tek pod'da bu kolaydır çünkü Spring local memory'de şu mapping'i bilir:

```text
username -> sessionId -> WebSocket connection
```

Ancak çoklu pod'da userB Pod-2'ye bağlıysa ve `convertAndSendToUser` Pod-1'de çalışıyorsa Pod-1'in local memory'sinde userB'nin session mapping'i olmayabilir.

Problem şudur:

```text
user -> session mapping Pod-2'nin memory'sindedir.
subscription registry Pod-2'nin memory'sindedir.
actual socket connection Pod-2 üzerindedir.
```

Pod-1 sadece `username = userB` bilgisini bilir. Bu username'in canlı socket'e nasıl çevrileceğini local olarak bilemez.

Bu yüzden user-specific messaging, distributed sistemlerde ayrıca dikkat ister.

## Broker Relay Sistemi Tamamen Stateless Yapar mı?

Hayır.

Broker relay mesajlaşma state'inin önemli bir kısmını pod dışına taşır ama WebSocket connection state hâlâ Spring pod üzerindedir.

Ayrılması gereken iki state türü vardır:

```text
1. Connection State
   Hangi client hangi pod'a bağlı?
   Socket açık mı?
   Heartbeat geliyor mu?
   Client reconnect etti mi?

2. Messaging State
   Hangi destination'a kim subscribe?
   Hangi mesaj hangi route'a gidecek?
   Broker hangi consumer'a delivery yapacak?
```

Broker relay ikinci tarafı iyileştirir. Ancak connection ownership hâlâ pod üzerindedir.

Bu yüzden Pod-2 ölürse:

```text
Client B'nin WebSocket bağlantısı kopar.
RabbitMQ mesaj routing yapabilir.
Ama ölü socket'e mesaj teslim edemez.
Client reconnect etmek zorundadır.
```

## Ölçekleme Açısından Kritik Noktalar

WebSocket sistemlerinde ölçekleme sadece request sayısıyla ölçülmez.

Dikkat edilmesi gereken metrikler:

```text
Aktif WebSocket connection sayısı
Connection başına memory tüketimi
Subscription sayısı
Destination fan-out oranı
Heartbeat trafiği
Socket buffer kullanımı
Slow consumer sayısı
Reconnect oranı
Pod başına file descriptor kullanımı
Broker latency
Broker queue depth
```

HTTP sisteminde ölçekleme çoğunlukla request workload dağıtma problemidir.

WebSocket sisteminde ölçekleme ise connection ownership ve state coordination problemidir.

## Sektör Standardı Akış

Production'a daha uygun genel akış:

```text
1. Client Spring WebSocket endpoint'e bağlanır.
2. STOMP CONNECT frame gönderilir.
3. Spring authentication / principal mapping yapar.
4. Client ilgili destination'a SUBSCRIBE olur.
5. Spring subscription bilgisini broker relay üzerinden harici broker'a taşır.
6. Backend event oluşur.
7. Event /topic veya /queue destination'a publish edilir.
8. RabbitMQ / ActiveMQ mesajı ilgili podlara dağıtır.
9. Her pod kendi local WebSocket session'larına mesajı yazar.
10. Client koparsa reconnect, resubscribe ve missed-event stratejisi devreye girer.
```

## Bizim Mimari Karar Notlarımız

### Simple Broker

Basit geliştirme, local demo veya tek instance senaryolarında kullanılabilir.

Ancak çoklu pod, autoscaling ve global broadcast gerekiyorsa yeterli değildir.

### Broker Relay

Çoklu pod mimarisinde tercih edilmelidir.

Özellikle şu ihtiyaçlar varsa broker relay düşünülmelidir:

```text
Global topic broadcast
Cross-pod message distribution
User-specific messaging
Autoscaling
Notification sistemi
Canlı dashboard
Admin broadcast
Presence-aware event delivery
```

### Sticky Session

Tam çözüm değildir. Sadece connection routing'i stabilize eder.

### External Broker

Mesajlaşma problemini doğru yere taşır ama sisteme yeni operasyonel yük getirir:

```text
Broker monitoring
High availability
Queue policy
Heartbeat tuning
Reconnect handling
Backpressure management
Security configuration
```

## Teknik Terimler Sözlüğü

- WebSocket: Client ve server arasında uzun ömürlü, çift yönlü bağlantı sağlayan protokol.
- Full-Duplex: İki tarafın aynı bağlantı üzerinden birbirine bağımsız şekilde mesaj gönderebilmesi.
- STOMP: WebSocket üzerinde destination, subscription ve message semantics sağlayan mesajlaşma protokolü.
- Destination: STOMP mesajlarının gönderildiği mantıksal adres. Örnek: `/topic/orders`.
- Topic: Bir mesajın birden fazla subscriber'a dağıtıldığı broadcast yapısı.
- Queue: Genellikle point-to-point veya user-specific mesajlaşma için kullanılan destination türü.
- In-Memory Broker: Spring uygulamasının içinde çalışan, subscription ve routing bilgisini local memory'de tutan basit broker.
- Broker Relay: Spring'in mesajları harici bir broker'a aktardığı mimari yaklaşım.
- RabbitMQ: Message broker. STOMP veya Web-STOMP plugin'leriyle WebSocket/STOMP mimarilerinde kullanılabilir.
- Web-STOMP: Browser client'ın WebSocket üzerinden doğrudan RabbitMQ ile STOMP konuşmasını sağlayan RabbitMQ plugin'i.
- Sticky Session: Load balancer'ın aynı kullanıcıyı aynı backend instance'a yönlendirmesi.
- Connection State: Canlı WebSocket bağlantısına ait state. Hangi client'ın hangi pod'a bağlı olduğu bilgisidir.
- Subscription Registry: Hangi session'ın hangi destination'a subscribe olduğunu tutan kayıt yapısı.
- User Presence: Bir kullanıcının online/offline/idle gibi durumlarının sistem genelinde takip edilmesi.
- Fan-out: Bir mesajın birden fazla client'a veya subscriber'a dağıtılması.
- Backpressure: Mesaj üretim hızının tüketim hızından fazla olması sonucu sistemde basınç oluşması.
- Heartbeat: Connection'ın canlı olup olmadığını anlamak için periyodik gönderilen sinyal.

## Sonuç

Spring WebSocket ve STOMP mimarisinde en kritik ayrım şudur:

> STOMP protokoldür.
> Broker mimarisi ise mesajın dağıtık sistemde gerçekten nasıl dolaşacağını belirler.

`enableSimpleBroker` tek pod için kullanışlıdır ancak çoklu pod ortamında subscription ve session state local kaldığı için global mesaj dağıtımı sağlamaz.

`enableStompBrokerRelay` ile RabbitMQ veya ActiveMQ gibi harici broker kullanıldığında mesajlaşma state'i pod dışına taşınır ve cross-pod message distribution mümkün olur.

Ancak broker relay sistemi tamamen stateless yapmaz. WebSocket connection state hâlâ Spring pod üzerindedir. Bu nedenle reconnect, heartbeat, presence management, missed messages ve pod crash senaryoları ayrıca tasarlanmalıdır.

Production-ready WebSocket mimarisi şu gerçeği kabul ederek tasarlanmalıdır:

> WebSocket scaling, request scaling değildir.
> WebSocket scaling; connection ownership, distributed message routing ve state coordination problemidir.

## Araştırma Keywordleri

- `spring websocket stomp broker relay`
- `spring enableSimpleBroker vs enableStompBrokerRelay`
- `spring websocket rabbitmq stomp broker relay`
- `rabbitmq stomp plugin spring websocket`
- `rabbitmq web stomp vs stomp plugin`
- `spring simpMessagingTemplate convertAndSend distributed`
- `spring convertAndSendToUser multiple instances`
- `websocket scaling kubernetes spring boot`
- `websocket sticky session limitations`
- `distributed websocket architecture`
- `websocket presence management`
- `websocket heartbeat reconnect strategy`
- `stomp broker relay rabbitmq scaling`
- `spring websocket user destination multiple nodes`
- `websocket connection state distributed systems`
- `pub sub for websocket scaling`
