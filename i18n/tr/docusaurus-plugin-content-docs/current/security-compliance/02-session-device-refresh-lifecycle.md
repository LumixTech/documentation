---
title: Session, Device ve Refresh Token Yaşam Döngüsü
description: Redis merkezli session, access token ve refresh token yaşam döngüsü tasarımı; revoke, rotation ve logout-all davranışlarını tanımlar.
sidebar_position: 2
---

## Giriş

JWT imzalamak kimlik doğrulamanın sadece bir parçasıdır. Üretim ortamında esas farkı; oturumların nerede aktif olduğu, hangi cihazların açık kaldığı ve access/refresh token yaşam döngüsünün nasıl yönetildiği belirler.

Bu doküman Redis tabanlı `USER_SESSIONS`, `ACCESS_TOKENS` ve `REFRESH_TOKENS` yaşam döngüsünü tanımlar.

## Neden Önemli

Yaşam döngüsü tasarımı yoksa:

- logout davranışı tutarsızlaşır,
- şüpheli oturum hızlıca izole edilemez,
- destek ve uyumluluk ekipleri kullanıcı aktivitesini net açıklayamaz.

Ürün için `logout`, `logout-all`, token reuse ve revocation davranışları deterministik olmalıdır.

## Temel Kavramlar

- `USER_SESSIONS`: aktif/pasif device session kayıtlarının ana listesi.
- `ACCESS_TOKENS`: `jti` bazlı kısa ömürlü access token durum kayıtları.
- `REFRESH_TOKENS`: session'a bağlı refresh token zincir kayıtları.
- `device session`: kullanıcı kimliği ile cihaz bağlamı arasındaki kayıt.
- Absolute timeout: aktiviteden bağımsız azami session ömrü.
- Idle timeout: inaktif kalma sonrası session kapanışı.
- Refresh token family: rotation ve replay tespiti için token soy zinciri.

## Gerçek Ürün Geliştirmede Problem

Sık görülen açıklar:

- Eski refresh token iptal edilmediği için çalınmış token uzun süre kullanılabilir.
- `logout` sadece UI/cache tarafında kalır, token state'i aktif kalır.
- `logout-all` tetiklenir ama tüm cihaz/session/token anahtarları kapanmaz.

Bu boşluklar güvenlik belirsizliği ve zor incident response doğurur.

## Yaklaşım / Tasarım

### Redis Veri Modeli Sorumlulukları

- `USER_SESSIONS`
  - key: `session:{session_id}`
  - alanlar: `user_id`, `tenant_id`, device metadata, `status`, reason code, zaman damgaları

- `ACCESS_TOKENS`
  - key: `token:access:{jti}`
  - alanlar: `session_id`, `user_id`, `status`
  - TTL access token süresiyle hizalı

- `REFRESH_TOKENS`
  - key: `token:refresh:{hash}`
  - alanlar: `session_id`, `user_id`, `status`, `parent_token_id`, zaman damgaları
  - TTL refresh token süresiyle hizalı

- `USER -> SESSIONS` indeksi
  - key: `user:sessions:{user_id}`
  - değer: `logout-all` ve incident response için aktif session id listesi

### Yaşam Döngüsü Kuralları

- Her login yeni `device session` üretir (politika reuse izin vermezse).
- Her authenticated request JWT + Redis state (`jti`, `session_id`, session status) doğrulaması yapar.
- Başarılı her refresh çağrısı refresh token'ı döndürür (rotate) ve access token state'ini yeniler.
- `logout` sadece ilgili session ve ona bağlı access/refresh state'i kapatır.
- `logout-all` kullanıcıya ait tüm aktif session ve access/refresh token state'lerini kapatır.

## Sektör Standardı / Best Practice

- Refresh token'ları düz metin değil hash olarak sakla.
- Access token için `jti` kullan ve hedefli revoke yap.
- Session state ile token state'i ayrı tut, referanslarını açık bağla.
- Doğal expiry için Redis TTL, zorunlu kapatma için explicit status transition kullan.
- Revoke reason alanlarını audit ve incident analizi için kaydet.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
on AUTH_REQUEST(access_token):
  claims = verify_jwt(access_token)

  token = redis_get("token:access:" + claims.jti)
  session = redis_get("session:" + claims.session_id)

  if token missing or token.status != 'active':
    deny
  if session missing or session.status != 'active':
    deny

  allow

on REFRESH_ATTEMPT(refresh_token, session_context):
  token = redis_get("token:refresh:" + hash(refresh_token))

  if token not found:
    deny

  if token.status in ['revoked', 'expired']:
    deny

  if token.status == 'rotated':
    mark_session_high_risk(token.session_id)
    revoke_session(token.session_id)
    revoke_access_tokens_by_session(token.session_id)
    deny

  if session_context mismatches token.session_id policy:
    deny

  next_refresh = create_refresh_token(parent_id=token.id, session_id=token.session_id)
  next_access = create_access_token(session_id=token.session_id, jti=random_id())

  mark_token_status(token.id, 'rotated')
  redis_set("token:refresh:" + hash(next_refresh), session_id=token.session_id, status='active')
  redis_set("token:access:" + next_access.jti, session_id=token.session_id, status='active', ttl=next_access.ttl)

  return next_access, next_refresh

on LOGOUT_ALL(user_id):
  for session in active_sessions(user_id):
    revoke_session(session.id, reason='user_requested_logout_all')
    revoke_access_tokens_by_session(session.id)
    revoke_refresh_tokens_by_session(session.id)

  return success
```

## Bizim Notlarımız / Takım Kararları

- Redis tabanlı `USER_SESSIONS`, `ACCESS_TOKENS` ve `REFRESH_TOKENS` zorunlu; stateless-only auth modelini kabul etmiyoruz.
- Cihaz bazlı görünürlük kullanıcı güvenliği ve destek operasyonu için zorunludur.
- Forensic izlenebilirlik için silme yerine açık durum geçişlerini tercih ediyoruz.
- Bu tasarım [JWT ve Refresh Token ile Spring Security Authentication Flow](./auth-jwt-refresh-flow) ile birlikte düşünülmelidir.

## Sözlük

- `session`: sunucu tarafı kalıcı kimlik doğrulama bağlamı.
- `device session`: belirli cihaz/client'e bağlı session.
- `jti`: access token state takibi için benzersiz kimlik.
- Rotation: yeni refresh token üretip öncekini pasifleme.
- Revocation: yönetimsel veya kullanıcı tetiklemeli zorunlu geçersizleme.
- Token family: rotation ile bağlı refresh token zinciri.

## Araştırma Keywordleri

- `redis token session lifecycle design`
- `jwt jti revocation strategy`
- `refresh token family rotation strategy`
- `device session lifecycle design`
- `logout all sessions token revocation`

## Sonuç

Yaşam döngüsü kontrolü, basit login sistemini üretim sınıfı auth platformuna çeviren ana farktır. Redis'te açık token/session state modeli ile güvenlik kontrolü, operasyonel netlik ve kullanıcı güveni korunur.
