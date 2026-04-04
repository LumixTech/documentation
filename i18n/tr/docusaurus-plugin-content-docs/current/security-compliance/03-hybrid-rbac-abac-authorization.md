---
title: Hibrit Yetkilendirme Modeli - RBAC + ABAC
description: role_permission, user_permission, common_permission ve allow/deny öncelik kuralı ile yetkilendirme modeli.
sidebar_position: 3
---

## Giriş

Sadece rol tabanlı model (RBAC), kurumsal ürünlerde istisnaları yönetmekte zorlanır. Bu nedenle RBAC ve ABAC birlikte ele alınmalıdır.

## Neden Önemli

RBAC tek başına kullanıldığında:

- role explosion oluşur,
- endpoint koduna dağılmış istisnalar artar,
- kararların audit edilebilirliği azalır.

## Temel Kavramlar

- `role_permission`: rolle gelen yetki.
- `user_permission`: kullanıcıya özel override yetkisi.
- `common_permission`: ortak temel yetki seti.
- `allow` / `deny`: karar etkisi.
- ABAC attribute'ları: `tenant_id`, sahiplik, departman, risk bağlamı vb.

## Gerçek Ürün Geliştirmede Problem

Gerçek senaryolarda aynı role sahip kullanıcılar farklı tenant veya bağlam nedeniyle farklı karar almalıdır. Sadece role bakmak yanlış pozitif/negatif sonuç üretir.

## Yaklaşım / Tasarım

Önerilen değerlendirme sırası:

1. Kimlik ve bağlam çözümle.
2. `common_permission` yükle.
3. `role_permission` setini birleştir.
4. `user_permission` override uygula.
5. ABAC koşullarını doğrula.
6. `allow`/`deny` precedence kuralını uygula.

Önerilen öncelik:

- Açık `deny`, açık `allow`'u ezer.
- `user_permission`, tanımlı alanlarda `role_permission` üzerine çıkabilir.
- ABAC başarısızsa geçici allow kararı düşer.

Veri sınırı için: [Tenant-based Row-Level Security (RLS)](../database-architecture/tenant-based-rls-policy-design).

## Sektör Standardı / Best Practice

- Politika dilini küçük ve deterministik tut.
- Permission ataması ile policy evaluation katmanını ayır.
- Deny kararlarını da logla.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
authorize(user, action, resource, context):
  permissions = common_permission(action)
  permissions += role_permission(user.roles, action)
  permissions += user_permission(user.id, action)

  if has_explicit_deny(permissions, resource):
    return deny

  if not has_any_allow(permissions, resource):
    return deny

  if not abac_match(user, resource, context):
    return deny

  return allow
```

## Bizim Notlarımız / Takım Kararları

- Model hibrit olacak: `user_permission` + `role_permission` + `common_permission`.
- `allow`/`deny` precedence açık ve dokümante olmalı.
- Yetki tasarımı `role -> permission -> endpoint/use-case` hattında yapılmalı.

## Sözlük

- RBAC: Role-based access control.
- ABAC: Attribute-based access control.
- Precedence: Çakışmada karar öncelik kuralı.

## Araştırma Keywordleri

- `hybrid rbac abac authorization`
- `allow deny precedence model`
- `user permission override strategy`

## Sonuç

Hibrit model, hem yönetilebilirlik hem esneklik sağlar. RBAC temel omurgayı kurar, ABAC gerçek bağlamı devreye alır.
