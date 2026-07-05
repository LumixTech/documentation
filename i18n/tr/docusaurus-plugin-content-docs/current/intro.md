---
sidebar_position: 1
title: Dokümantasyon Portalı Ana Sayfa
description: Güvenlik, uyumluluk, veritabanı tasarımı, frontend yönlendirme ve mühendislik kararlarını kapsayan mimari odaklı dokümantasyon.
---

## Giriş

Bu portal, teknik çalışma notlarımızı uzun vadeli bakım yapılabilir bir Docusaurus yapısına dönüştürür. İç eğitim, onboarding, mimari hizalama ve gelecekte bloglaştırma hedefleri için tasarlanmıştır.

## Neden Önemli

Ürün geliştirme; güvenlik, backend, frontend, veri ve uyumluluk katmanlarının birlikte düşünülmesini gerektirir. Bu yapı, sadece "nasıl yaptık" değil "neden böyle yaptık" sorusunu da cevaplar.

## Temel Kavramlar

- Mimari kararlar katmanlar arasında birbirine bağlıdır.
- Güvenlik ve uyumluluk sonradan eklenen işler değil, ürün gereksinimidir.
- Veritabanı ve frontend kararları kural/politika odaklı yönetilmelidir.

## Gerçek Ürün Geliştirmede Problem

Bilgi dağınık notlarda kaldığında bağlam kaybolur, ekip aynı hataları tekrarlar ve karar geçmişi unutulur.

## Yaklaşım / Tasarım

Portal omurgası şu bölümlerden oluşur:

- [Güvenlik ve Uyumluluk](./category/security--compliance)
- [Veritabanı Mimarisi](./category/database-architecture)
- [Frontend Mimarisi](./category/frontend-architecture)
- [Mühendislik Notları](./category/engineering-notes)
- [Sözlük](./category/glossary)

## Sektör Standardı / Best Practice

- Konuları kapsamı net olacak şekilde parçala.
- İlgili sayfalar arasında bağlam linkleri ver.
- Terminolojiyi merkezi sözlükle sabitle.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
for each new_topic:
  classify_into_category(new_topic)
  write_context_problem_tradeoff(new_topic)
  add_glossary_terms(new_topic)
  link_related_docs(new_topic)
```

## Bizim Notlarımız / Takım Kararları

- Düz dosya yığını yerine ölçeklenebilir bilgi mimarisi tercih ediyoruz.
- Her sayfada karar, trade-off ve operasyon etkisi birlikte anlatılmalıdır.
- Ortak terimler [Ortak Terminoloji Sözlüğü](./glossary/glossary) sayfasından yönetilir.

## Sözlük

- Dokümantasyon portalı: Mühendislik bilgisinin merkezi bilgi tabanı.
- Karar gerekçesi: Seçilen teknik yolun nedenini açıklayan kısım.
- Çapraz linkleme: İlgili konuların doğal bağlantısı.

## Araştırma Keywordleri

- `engineering documentation information architecture`
- `docusaurus maintainable docs structure`
- `architecture decision storytelling`

## Sonuç

Bu portal ürünle birlikte evrilmelidir. Yeni konular eklenirken kategori disiplini, terim birliği ve karar gerekçesi korunursa dokümantasyon uzun vadede değer üretir.
