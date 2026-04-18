---
title: JWT ve Refresh Token ile Spring Security Authentication Flow
description: Redis tabanlı stateful access token, refresh token ve device session yaşam döngüsü ile kimlik doğrulama mimarisi.
sidebar_position: 1
---

## Giriş

Authentication flow ürünün ana güvenlik kapısıdır. Bu katman zayıfsa aşağıdaki authorization, tenant izolasyonu ve `audit log` kontrolleri de güvenilmez hale gelir.

Bu tasarım Redis tabanlı stateful model kullanır:

- `access token`: kısa ömürlü JWT, Redis'te state takibi var
- `refresh token`: stateful, revoke edilebilir, Redis'te rotation yönetimli
- `session` ve `device session`: Redis'te yaşam döngüsü yönetimli

Detaylı yaşam döngüsü için: [Session, Device ve Refresh Token Yaşam Döngüsü](./session-device-refresh-lifecycle).

## Neden Önemli

Sadece JWT imza doğrulaması üretim ortamı için yeterli değildir:

- `logout-all` merkezi token/session state olmadan güvenilir uygulanamaz.
- Token hırsızlığında aktif token zinciri izlenmiyorsa hızlı tepki verilemez.
- Cihaz bazlı güven ve revoke kararları sunucu tarafı state gerektirir.

Redis destekli state modeli, düşük gecikmeli doğrulama ile operasyonel kontrolü birlikte sağlar.

## Temel Kavramlar

- `access token`: API çağrılarında taşınan JWT; `jti`, `session_id` ve kısa TTL içerir.
- `refresh token`: yeni access token üretimi için kullanılır; hashlenerek Redis'te izlenir.
- `session`: kullanıcı + cihaz bağlamını tutan sunucu tarafı kayıt.
- `device session`: cihaz/app instance bazlı oturum sınırı.
- Rotation: her refresh çağrısında yeni refresh token üretip eskisini kapatma.
- Revocation: süresi dolmadan token/session geçersizleme.

## Gerçek Ürün Geliştirmede Problem

Kullanıcılar birden fazla cihazdan giriş yapar, ağ değiştirir, cihaz kaybeder. Güvenlik olaylarında anlık revoke ve izlenebilirlik gerekir.

`access token` sadece stateless ele alınırsa:

- güvenlik ekibi riskli oturumu anında kapatamaz,
- destek ekibi hangi aktif token/session'ın hangi aksiyonu yaptığını açıklayamaz,
- uyumluluk incelemelerinde revoke zamanlaması kanıtlanamaz.

## Yaklaşım / Tasarım

### Endpoint Akışı

- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `POST /auth/logout-all`

### Tasarım İlkeleri

- JWT kriptografik doğrulaması (`iss`, `aud`, imza, exp) request path'te korunur.
- Buna ek olarak Redis'te token/session durumu (`active`, `revoked`, `expired`) doğrulanır.
- Tüm tokenlar `device session` kayıtlarına bağlanır.
- Refresh token rotation ve replay detection zorunludur.
- Logout akışlarında access + refresh + session birlikte revoke edilir.

### Spring Security Entegrasyonu

- Route korumaları `SecurityFilterChain` içinde yönetilir.
- Önce JWT claim doğrulanır, sonra Redis'te `jti` ve `session_id` state kontrolü yapılır.
- Refresh lifecycle servis katmanında açık state geçişleriyle yönetilir.

## Sektör Standardı / Best Practice

- Kısa TTL access token
- Redis tabanlı token/session state yönetimi
- Refresh token hash + rotation
- Açık logout/revoke semantiği
- Session etkileyen olaylar için audit izlenebilirliği

Spring Security kullanımında ayrım genelde şöyledir:

- request authentication/authorization filter chain'de,
- token/session lifecycle orkestrasyonu servis katmanında.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
on LOGIN(credentials, device_info):
  user = authenticate(credentials)
  if user invalid:
    deny

  session = create_session(user.id, device_info)
  redis_set("session:" + session.id, status='active', user_id=user.id, tenant_id=user.tenant_id)

  access_token = issue_access_token(user.id, session.id, user.tenant_id, jti=random_id())
  refresh_token = issue_refresh_token(session.id)

  redis_set("token:access:" + access_token.jti, session_id=session.id, status='active', ttl=access_token.ttl)
  redis_set("token:refresh:" + hash(refresh_token), session_id=session.id, status='active', ttl=refresh_token.ttl)

  return access_token, refresh_token

on AUTHENTICATED_REQUEST(access_token):
  claims = verify_jwt(access_token)
  token_state = redis_get("token:access:" + claims.jti)
  session_state = redis_get("session:" + claims.session_id)

  if token_state missing or token_state.status != 'active':
    deny
  if session_state missing or session_state.status != 'active':
    deny

  allow

on REFRESH(refresh_token):
  record = redis_get("token:refresh:" + hash(refresh_token))
  if record missing or record.status != 'active':
    deny

  rotate_refresh(record)
  new_access = issue_access_token(record.user_id, record.session_id, record.tenant_id, jti=random_id())
  new_refresh = issue_refresh_token(record.session_id)

  redis_set("token:access:" + new_access.jti, session_id=record.session_id, status='active', ttl=new_access.ttl)
  redis_set("token:refresh:" + hash(new_refresh), session_id=record.session_id, status='active', ttl=new_refresh.ttl)

  return new_access, new_refresh

on LOGOUT(session_id):
  revoke_session(session_id)
  revoke_access_tokens_by_session(session_id)
  revoke_refresh_tokens_by_session(session_id)

on LOGOUT_ALL(user_id):
  revoke_all_sessions(user_id)
  revoke_all_access_tokens(user_id)
  revoke_all_refresh_tokens(user_id)
```

## Bizim Notlarımız / Takım Kararları

- Runtime kontrol için tam stateful auth modeli kullanıyoruz: `access token`, `refresh token` ve `session` Redis'te takip ediliyor.
- `logout-all` iş gereksinimidir, opsiyon değildir.
- Revoke kararları açık token/session durum geçişleriyle izlenebilir olmalıdır.
- Yetki davranışı [Hibrit Yetkilendirme Modeli: RBAC + ABAC](./hybrid-rbac-abac-authorization) ile uyumlu kalmalıdır.

## Sözlük

- `access token`: API çağrılarında kullanılan kısa ömürlü JWT; Redis token/session state'i ile doğrulanır.
- `refresh token`: yeni access token üretimi için kullanılan uzun ömürlü token.
- `session`: sunucu tarafı yönetilen kimlik doğrulama bağlamı.
- `device session`: belirli cihaz/client için oturum kaydı.
- `jti`: Redis state lookup için kullanılan benzersiz token kimliği.
- Rotation: yeni refresh token üretirken eskisini kapatma.
- Revocation: süresi dolmadan sunucu taraflı geçersizleme.

## Araştırma Keywordleri

- `spring security jwt redis token state`
- `stateful access token redis session validation`
- `refresh token rotation replay detection`
- `logout all devices authentication design`
- `jwt jti revocation redis`

## Sonuç

Üretim seviyesinde auth mimarisi, JWT bütünlük kontrolünü stateful operasyonel kontrolle birleştirmelidir. Token ve session runtime state'ini Redis'te tutmak; hızlı revoke, güçlü incident response ve net operasyonel görünürlük sağlar.
