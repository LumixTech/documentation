---
sidebar_position: 1
title: Giriş ve Yol Haritası
description: Lumix dökümantasyon portali — hem proje referansı hem de takımın öğrenme alanı.
---

## Bu portal ne?

Buraya iki amaçla geliyoruz:

1. **Proje referansı** — Lumix sisteminde **ne** kullanıyoruz, **neden** seçtik, **nasıl** kullanıyoruz, **neyi** çözüyor. Yeni gelen geliştirici tek başına okuyup sisteme dahil olabilmeli.
2. **Öğrenme alanı** — Kullanılan tüm teknolojileri ve mimari yaklaşımları **sıfırdan** öğrenebilmeli. Bir konuya tıkladığımızda hem temel kavram hem de bizim projemize özel uyarlama yan yana durmalı.

Yani bu site sadece "biz ne yaptık"ı değil, "bunu öğrenmek isteyen biri **nereden başlasın**"ı da anlatır.

## Hangi seviyeye konuşuyoruz?

- **Konunun ne olduğunu** hiç bilmeyene bile mantıklı gelecek bir giriş yazıyoruz.
- Ardından **bizim projemizdeki kullanımına** geçiyoruz — versiyon, konfig, tasarım kararı.
- Trade-off'ları, **elediğimiz alternatifleri**, dikkat edilecek tuzakları açıkça yazıyoruz.
- Son olarak **daha derine inmek için** linkler ve arama anahtar kelimeleri bırakıyoruz.

## Her sayfanın iskeleti

Tüm doküman sayfaları aynı 10 başlığı takip eder:

1. **Bu sayfa ne anlatıyor?** — Tek paragraf özet
2. **Bu nedir? (Sıfırdan)** — Hiç bilmeyene 5 dakikada anlat
3. **Hangi problemi çözüyor?** — Olmasaydı ne olurdu
4. **Nasıl çözüyor? (Çalışma prensibi)** — Adım adım mekanizma
5. **Biz projemizde nasıl kullanıyoruz?** — Spesifik karar
6. **Neden bu seçildi? (Alternatifler & trade-off)** — Eleneneler
7. **Pratik örnek** — Gerçek kod/config
8. **Dikkat edilecek tuzaklar** — Anti-pattern, performans riski
9. **Diğer konularla ilişkisi** — Bağlantılı doc'lar
10. **Daha derine inmek için** — Resmi link, arama keyword'leri
11. **Sözlük** — Sayfada geçen terimler

Bu şablonu her doc'ta görürsen kafan karışmaz, ne aradığını biliyorsun.

## Nereden başlamalı?

İlk gelenler için sırayla okuma yolu:

1. [Vizyon ve Hedefler](./overview/vision-and-goals) — projeyi neden yapıyoruz, müşteri kim, hangi sorunu çözüyor
2. [Teknoloji Kararları](./overview/technology-stack-decisions) — tek sayfada tüm yığın
3. [Genel Mimari](./overview/overall-architecture) — sistemin kuş bakışı görünümü
4. [Öğrenme Yolu](./overview/learning-path) — konuları hangi sırada okumalısın

Ardından domain modeline gir:

5. [Installation / Tenant / Scope](./tenancy-and-domain-model/installation-tenant-scope) — multi-tenancy modeli, "Hüseyin öğretmen / bölge müdürü" örnekleri
6. [Domain Servisleri](./tenancy-and-domain-model/domain-services-overview) — 10 microservice ve sorumlulukları

Sonrası kendi ilgi alanına göre kategoriler arasında gezinebilirsin.

## Sözlük her zaman elinizin altında

Bilmediğin terim gördüğünde: [Sözlük](./glossary/glossary) sayfası tüm proje terimlerinin tek listesi. Yeni terim eklenirken önce sözlüğe yazılır, sonra başka doc'larda kullanılır.

## Bu doc'lar kim için yazıldı?

- **Yeni gelen geliştirici** — sisteme dahil olurken
- **Mevcut takım** — karar tartışırken eski kararları unutmamak için
- **Kendi öğrenme yolculuğumuz** — yeni teknoloji öğrenirken hem bilgi hem proje referansı tek yerde
- **Gelecekteki blog yazıları** — burası aynı zamanda ileride yazılacak engineering blog post'larının ham maddesi

## Sürekli güncellenir

Bu site **bitmiş bir kitap değil**. Yeni karar verdikçe, yeni teknoloji eklendikçe, eski karar değiştikçe güncellenir. Eskiyen kararlar arşivlenmek yerine değişiklik tarihçesiyle birlikte tutulur — "neden o zaman A demiştik, neden şimdi B?" sorusunun cevabı kayıt altında kalır.
