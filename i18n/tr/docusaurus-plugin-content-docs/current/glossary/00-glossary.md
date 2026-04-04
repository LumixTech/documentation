---
title: Ortak Terminoloji Sözlüğü
description: Güvenlik, veritabanı, frontend ve uyumluluk dokümanlarında kullanılan standart terimler.
sidebar_position: 1
---

## Giriş

Bu sözlük, portal genelinde kullanılan ortak teknik dili sabitler. Aynı kavramın her belgede aynı isimle geçmesi hedeflenir.

## Neden Önemli

Terminoloji kayması; yanlış yorum, hatalı implementasyon ve onboarding gecikmesine yol açar.

## Temel Kavramlar

- Terim standardizasyonu
- Domain dili ile implementasyon dili uyumu
- Uzun vadeli bakım için kararlı sözlük

## Gerçek Ürün Geliştirmede Problem

`role_permission`, `role permission` ve `role-permission` gibi farklı yazımlar, aynı kavramı farklılaştırarak ekipte gürültü üretir.

## Yaklaşım / Tasarım

Aşağıdaki terimler tüm dokümanlarda standart kullanılmalıdır:

- `access token`
- `refresh token`
- `session`
- `device session`
- `role_permission`
- `user_permission`
- `common_permission`
- `allow`
- `deny`
- `tenant_id`
- `RLS policy`
- `audit log`
- `retention policy`
- `anonymization`
- `DSAR workflow`
- `smart navigation`
- `read replica`
- `write primary`

## Sektör Standardı / Best Practice

- Politika kritik terimler için eşanlamlı kullanımını azalt.
- Yeni terim eklemeden önce sözlüğe kaydet.
- Terim değişikliğini ekip duyurusu ile yap.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
on NEW_TERM_REQUEST(term):
  if equivalent_term_exists(term):
    reuse_existing_term
  else:
    add_to_glossary(term)
    announce_to_team(term)
```

## Bizim Notlarımız / Takım Kararları

- Bu sayfa proje terminolojisinin tek doğruluk kaynağıdır.
- Yeni mimari terimler önce bu sözlüğe eklenmelidir.

## Sözlük

- Terminoloji yönetimi: Teknik dilin merkezi ve tutarlı yönetimi.
- Ubiquitous language: Ekip genelinde paylaşılan ortak domain dili.
- Tek doğruluk kaynağı: Çelişkiyi azaltan resmi referans.

## Araştırma Keywordleri

- `engineering glossary governance`
- `ubiquitous language software teams`
- `technical terminology consistency`

## Sonuç

Doğru sözlük, düşük maliyetli ama etkisi yüksek bir mimari kontroldür. İletişimi hızlandırır, hatayı azaltır ve dokümantasyonu ölçeklenebilir hale getirir.
