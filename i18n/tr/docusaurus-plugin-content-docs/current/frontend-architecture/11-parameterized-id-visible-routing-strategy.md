---
title: Frontend URL Path Stratejisi - Görünür/Görünmez ID
description: URL'de ID görünürlüğünü Redux root state üzerinden yöneten config-driven route yaklaşımı.
sidebar_position: 1
---

## Giriş

URL tasarımı yalnızca frontend estetiği değildir; güvenlik, paylaşılabilirlik ve bakım maliyetini etkiler. ID'nin URL'de görünmesi merkezi bir ürün kararı olmalıdır.

## Neden Önemli

Dağınık route üretimi:

- ekranlar arası tutarsız URL,
- zor migration,
- deep-link kırılmaları üretir.

## Temel Kavramlar

- Visible ID route
- Invisible ID route
- Redux/root reducer policy state
- `smart navigation` merkezi yönlendirme bileşeni
- Canonical URL üretimi

## Gerçek Ürün Geliştirmede Problem

Her ekip route'u yerel komponent içinde farklı kurduğunda davranışlar dağılır ve konfigürasyon değişiklikleri riskli hale gelir.

## Yaklaşım / Tasarım

Politika state'i örneği:

- `routing.idVisibilityMode = 'visible' | 'hidden'`

`smart navigation` sorumlulukları:

- policy state'i selector ile okumak,
- canonical path üretmek,
- backward-compatible yönlendirmeyi korumak,
- query/history davranışını standartlaştırmak.

Veri sınırı perspektifi için: [Tenant Tabanlı Row-Level Security (RLS)](../database-architecture/tenant-based-rls-policy-design).

## Sektör Standardı / Best Practice

- Route üretimini tek modülde topla.
- UI komponentlerini state şekline bağımlı bırakma; selector kullan.
- Konfigürasyon odaklı davranışı feature-flag mantığıyla yönet.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
smart_navigation_go(entity_type, entity):
  mode = select_id_visibility_mode(root_state)

  if mode == 'visible':
    path = '/'+entity_type+'/'+entity.id
  else:
    path = '/'+entity_type+'/'+entity.public_key

  navigate(path)
```

## Bizim Notlarımız / Takım Kararları

- ID görünürlüğü sistem parametrelerinden yönetilecek.
- Parametreler Redux/root reducer/base state üzerinden taşınacak.
- Sayfa içi navigasyon bu state'e göre davranacak.
- `smart navigation` merkezi yapı olarak konumlanacak.

## Sözlük

- Canonical URL: bir kaynağın tercih edilen URL temsili.
- Deep link: uygulama içindeki spesifik duruma doğrudan giden link.
- Config-driven UI: davranışın konfigürasyonla yönetildiği yapı.

## Araştırma Keywordleri

- `redux driven routing`
- `config based url strategy`
- `smart navigation component react`

## Sonuç

URL davranışı merkezi politika olarak ele alınmalıdır. Bu yaklaşım ürün evrimi sırasında güvenli geçiş ve tutarlı kullanıcı deneyimi sağlar.
