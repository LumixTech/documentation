---
title: JWT ve Refresh Token ile Spring Security Authentication Flow
description: Stateless access token doğrulaması ile stateful refresh token ve device session yönetimini birleştiren kimlik doğrulama mimarisi.
sidebar_position: 1
---

## Giriş

Authentication flow ürünün ana güvenlik kapısıdır. Bu katman zayıfsa aşağıdaki authorization, tenant izolasyonu ve `audit log` kontrolleri de güvenilmez hale gelir.

Bu tasarım hibrit yaklaşım kullanır:

- `access token`: stateless, kısa ömürlü
- `refresh token`: stateful, revoke edilebilir
- `session` ve `device session`: stateful, yaşam döngüsü yönetimli

Detaylı yaşam döngüsü için: [Session, Device ve Refresh Token Yaşam Döngüsü](./session-device-refresh-lifecycle).

## Neden Önemli

Sadece JWT yaklaşımı gerçek üründe yetersiz kalır:

- `logout-all` güvenilir biçimde yönetilemez.
- Token hırsızlığında hızlı karşılık verilemez.
- Cihaz bazlı oturum davranışı açıklanamaz.

## Temel Kavramlar

- `access token`: API çağrılarında taşınan kısa ömürlü doğrulama token'ı.
- `refresh token`: yeni access token üretimi için kullanılan stateful kayıt.
- `session`: sunucu tarafı oturum bağlamı.
- `device session`: cihaz bazlı oturum kaydı.
- Rotation: refresh çağrısında token yenileme ve eskisini kapatma.
- Revocation: süre dolmadan token/oturum geçersizleme.

## Gerçek Ürün Geliştirmede Problem

Kullanıcılar birden fazla cihazdan giriş yapar, cihaz kaybeder, riskli oturum oluşur. Tamamen stateless modelde destek ve güvenlik ekipleri "hangi oturum ne yaptı" sorusunu cevaplamakta zorlanır.

## Yaklaşım / Tasarım

Temel endpoint akışı:

- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `POST /auth/logout-all`

Tasarım ilkeleri:

- Access doğrulaması stateless kalsın.
- Refresh ve session stateful izlensin.
- Refresh token `device session` ile ilişkilensin.
- Rotation + replay detection zorunlu olsun.

Spring Security entegrasyonu:

- Route korumaları `SecurityFilterChain` içinde.
- Domain seviyeli kontroller method-level authorization ile.
- Refresh lifecycle işlemleri servis katmanında yönetilsin.

## Sektör Standardı / Best Practice

- Kısa TTL access token
- DB'de izlenen refresh token
- Açık logout/revoke semantiği
- Güvenlik olayları için izlenebilir kayıt modeli

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
on LOGIN(credentials, device_info):
  user = authenticate(credentials)
  if user invalid:
    deny

  session = create_session(user.id, device_info)
  access_token = issue_access_token(user.id, session.id, user.tenant_id)
  refresh_token = issue_refresh_token(session.id)
  store_refresh(hash(refresh_token), session.id)
  return access_token, refresh_token

on REFRESH(refresh_token):
  record = find_active_refresh(hash(refresh_token))
  if record missing or invalid:
    deny

  rotate_refresh(record)
  new_access = issue_access_token(record.user_id, record.session_id, record.tenant_id)
  new_refresh = issue_refresh_token(record.session_id)
  return new_access, new_refresh

on LOGOUT_ALL(user_id):
  revoke_all_sessions(user_id)
  revoke_all_refresh_tokens(user_id)
```

## Bizim Notlarımız / Takım Kararları

- Hibrit model net: `access token` stateless, `refresh token` stateful, `session` stateful.
- `logout-all` iş gereksinimidir, opsiyon değildir.
- Yetki davranışı [Hibrit Yetkilendirme Modeli: RBAC + ABAC](./hybrid-rbac-abac-authorization) ile uyumlu olmalıdır.

## Sözlük

- Authentication: Kimlik doğrulama.
- Authorization: Yetki kontrolü.
- Rotation: Eski refresh token'ı kapatıp yenisini üretme.
- Revocation: Süre dolmadan geçersizleme.

## Araştırma Keywordleri

- `spring security jwt refresh token flow`
- `stateless access token stateful refresh token`
- `logout all devices authentication design`

## Sonuç

Üretim seviyesinde auth mimarisi, stateless ve stateful parçaları birlikte tasarlamalıdır. Bu denge hem performans hem operasyonel kontrol sağlar.
