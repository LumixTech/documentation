---
title: Vizyon ve Hedefler
description: Lumix neyi çözüyor, kim için, hangi prensiplerle.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Lumix projesinin **ne olduğunu**, **kimin için** olduğunu, **neden var olduğunu** ve **hangi prensiplerle** geliştirildiğini anlatır. Sonraki tüm teknik kararlar buradaki çerçeveye dayanır.

## 1. Ürünün özü

Lumix, eğitim kurumları için tasarlanmış **on-premise veya kendi bulutunda çalışabilen** bir okul yönetim platformudur. Hedef:

- Bir kurum (örnek: "Ömer Okulları") sistemi satın alır.
- Sistem kurumun kendi sunucusuna veya tercih ettiği bulutuna kurulur.
- Kurum içindeki her okul/şube ayrı bir **tenant** olarak yönetilir.
- Öğretmenler, öğrenciler, veliler, yöneticiler kendi rollerinin gerektirdiği kapsamda sistemi kullanır.

## 2. Çözmeye çalıştığımız problemler

| Problem | Mevcut durumun sıkıntısı | Bizim yaklaşımımız |
|---|---|---|
| Veri sahipliği endişesi | SaaS modelinde veri başkasının sunucusunda | Self-host: müşterinin kendi sunucusu |
| KVKK / data residency | Bulut sağlayıcı seçimi sınırlı | Müşteri kendi coğrafyasında tutar |
| Yetkilendirme karmaşıklığı | Rol bazlı sistemler okul/sınıf bazlı kısıt için yetersiz | RBAC + ABAC + organizasyonel scope hibriti |
| Modül silosu | Eski sistemlerde akademik / finans / iletişim ayrık | Event-driven entegre microservice'ler |
| Audit ve uyumluluk | Audit log'lar çoğu üründe sonradan eklenmiş | Audit log birinci sınıf tasarım kararı |
| Lisans yönetimi | Online/offline farkı düşünülmemiş | Lisans dosyası + opsiyonel online renewal |

## 3. Hedef kullanıcılar

- **Kurum (Installation)** — sistemi satın alan, kuran, müşteri statüsündeki organizasyon. Örnek: Ömer Okulları, X Vakfı.
- **Tenant (Okul)** — kurum içindeki bağımsız operasyonel birim. Örnek: Ömer Okulları → Kadıköy Şubesi, Beşiktaş Şubesi.
- **Yönetici** — kurum/tenant seviyesinde yönetim yapan kullanıcı.
- **Bölge müdürü** — birden fazla tenant üzerinde yetkili kullanıcı.
- **Öğretmen** — belirli sınıflar üzerinde yetkili kullanıcı.
- **Öğrenci**
- **Veli**
- **Rehber öğretmen (PDR)** — özel kategori veriye erişen kullanıcı.

## 4. Tasarım prensipleri (mimari karar verirken sorduğumuz sorular)

### 4.1. Self-host önce
Her teknoloji seçiminde "**self-host edilebilir mi?**" sorusunu sorarız. Lisanslı ürünler veya yalnız bulutta çalışan SaaS'lar tercih edilmez.

### 4.2. Müşteri başına izolasyon
Her müşteri **kendi K8s cluster'ında**, **kendi DB'sinde**, **kendi Kafka'sında**. Veri sızıntısı tek müşteriyi etkiler, sistem geneline yayılmaz.

### 4.3. Açık standart
Açık protokol ve standartları tercih ederiz: gRPC + Protobuf, OpenTelemetry, OCI image, S3-compatible storage, OpenAPI/AsyncAPI sözleşmeleri.

### 4.4. Karar yazılı
Önemli mimari kararlar **gerekçesiyle yazılı**. "Neden A değil B?" sorusunun cevabı portal içinde bulunmalı. Tartışma tekrarı engellenir.

### 4.5. Öğrenme alanı + üretim alanı bir arada
Bu dökümantasyon hem yeni geliştirici onboard'u, hem mevcut takımın öğrenmesi, hem de production referansı olarak çalışır. Her sayfa "ne, neden, nasıl, ne işe yarar" yapısını korur.

### 4.6. Audit edilebilirlik
Önemli aksiyonlar (auth, permission değişimi, ödeme, PDR erişimi, dosya işlemleri) **append-only audit log**'a yazılır. Sistemde "kim ne zaman ne yaptı" sorusu cevapsız kalmaz.

### 4.7. Defense in depth
Tek güvenlik katmanına güvenmeyiz:
- Endpoint guard + service guard + RLS + audit
- API Gateway rate limit + uygulama rate limit
- Vault'ta sır + envelope encryption + transport encryption

## 5. Kapsam dışı (şimdilik)

Bunlar **bilinçli olarak** kapsam dışında — gereksiz karmaşıklığa girmemek için:

- Multi-region active-active replication (her müşteri tek bölgede)
- Service mesh (Istio) — ileride mTLS gereksinimi büyürse eklenebilir
- AI/ML modülleri (separate roadmap)
- Real-time video/voice (chat var, ama WebRTC değil)
- Custom mobile MDM dağıtımı (App Store + Google Play yeterli)

## 6. Başarı kriteri

Lumix'in "başarılı" sayılması için:

- Yeni geliştirici **bir hafta içinde** kendi makinesinde tüm yığını ayağa kaldırabilmeli (Tilt ile).
- Yeni müşteri kurulumu **scripted onboarding** ile **45 dakikada** tamamlanabilmeli.
- Bir teknoloji kararına "neden?" diye sorulduğunda cevap **bu portalda** bulunabilmeli.
- Bir production incident'ında **15 dakika içinde** restore başlatılabilmeli (RPO 15dk / RTO 2h hedefi).
- Audit log üzerinden son 6 ayda "kim ne yaptı" sorgusu cevaplanabilmeli.

## 7. Diğer konularla ilişkisi

- [Teknoloji Kararları](./02-technology-stack-decisions.md) — bu prensipleri uygulayarak hangi teknolojileri seçtik
- [Genel Mimari](./03-overall-architecture.md) — sistemin kuş bakışı görünümü
- [Installation / Tenant / Scope](../01-tenancy-and-domain-model/01-installation-tenant-scope.md) — multi-tenancy modelinin detayı
