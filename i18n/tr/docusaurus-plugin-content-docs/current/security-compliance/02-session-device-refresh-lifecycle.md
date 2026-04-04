---
title: Session, Device ve Refresh Token Yaşam Döngüsü
description: USER_SESSIONS ve REFRESH_TOKENS için rotation, revoke ve logout-all davranışlarını tanımlayan yaşam döngüsü tasarımı.
sidebar_position: 2
---

## Giriş

JWT imzalamak tek başına yeterli değildir. Üretim ortamında esas farkı; oturumların nerede aktif olduğu, cihaz bazlı izleme ve refresh token yaşam döngüsü yaratır.

## Neden Önemli

Yaşam döngüsü tasarımı yoksa:

- logout davranışı tutarsızlaşır,
- şüpheli cihaz hızla devre dışı bırakılamaz,
- kullanıcı aktiviteleri açıklanamaz.

## Temel Kavramlar

- `USER_SESSIONS`: aktif/pasif oturumların ana kaydı.
- `REFRESH_TOKENS`: refresh zinciri ve durum kayıtları.
- `device session`: cihaz düzeyinde oturum modeli.
- Absolute timeout: toplam üst ömür sınırı.
- Idle timeout: inaktif kalma sonrası kapanış.

## Gerçek Ürün Geliştirmede Problem

Sık görülen açıklar:

- Rotation sonrası eski refresh token aktif kalır.
- `logout` sadece access token tarafında kalır.
- `logout-all` kalıcı token tablosuna yansımaz.

## Yaklaşım / Tasarım

`USER_SESSIONS` en az şu alanları içerir:

- user_id, session_id, tenant_id
- device metadata
- status (`active`, `revoked`, `expired`)
- timestamps ve reason codes

`REFRESH_TOKENS` en az şu alanları içerir:

- refresh_token_id, session_id
- hashed value
- parent token id (rotation zinciri)
- status (`active`, `rotated`, `revoked`, `expired`)

Temel kurallar:

- Her login yeni `device session` üretir (politika aksini söylemiyorsa).
- Her refresh başarılı çağrıda rotation yapılır.
- `logout` sadece ilgili session zincirini kapatır.
- `logout-all` kullanıcıya ait tüm session ve refresh kayıtlarını kapatır.

## Sektör Standardı / Best Practice

- Refresh token düz metin saklama, hash sakla.
- Reuse tespiti yüksek risk sinyali olarak ele alınmalı.
- Silmek yerine durum geçişleri ile iz bırak.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
on REFRESH_ATTEMPT(refresh_token):
  token = find_refresh(hash(refresh_token))

  if token missing:
    deny

  if token.status in ['revoked', 'expired']:
    deny

  if token.status == 'rotated':
    mark_session_high_risk(token.session_id)
    revoke_session(token.session_id)
    deny

  rotate_token(token)
  issue_new_access_and_refresh(token.session_id)
```

## Bizim Notlarımız / Takım Kararları

- JWT-only modeli kabul etmiyoruz.
- `USER_SESSIONS` + `REFRESH_TOKENS` zorunlu.
- Cihaz bazlı oturum yönetimi ve `logout-all` birinci sınıf gereksinim.
- Bu tasarım [JWT ve Refresh Token ile Spring Security Authentication Flow](./auth-jwt-refresh-flow) ile birlikte düşünülmelidir.

## Sözlük

- Session invalidation: oturumun zorla kapatılması.
- Token family: rotation zincirindeki token ailesi.
- Revoke: yönetimsel veya kullanıcı kaynaklı geçersizleme.

## Araştırma Keywordleri

- `device based session lifecycle`
- `refresh token rotation best practices`
- `logout all session revocation design`

## Sonuç

Yaşam döngüsü yönetimi, auth sistemini gerçek ürün seviyesine çıkarır. Kontrol, izlenebilirlik ve güvenlik tepkisi bu katmanda şekillenir.
