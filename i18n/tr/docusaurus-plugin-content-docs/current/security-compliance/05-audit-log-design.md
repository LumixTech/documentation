---
title: Audit Log Tasarımı
description: Kim ne yaptı, ne zaman yaptı sorularını yanıtlayan AUDIT_LOGS tasarımı ve kritik aksiyon listesi.
sidebar_position: 5
---

## Giriş

`audit log`, uygulama debug log'u değildir. Amaç; kritik aksiyonlarda "kim, ne yaptı, ne zaman" sorusuna kanıt üretmektir.

## Neden Önemli

Özellikle payment, sağlık, counseling/PDR ve yetki değişimi gibi alanlarda audit eksikliği doğrudan risk üretir:

- olay incelemesi uzar,
- karar izi kaybolur,
- uyumluluk denetimleri zora girer.

## Temel Kavramlar

- Append-only kayıt yaklaşımı
- Actor-action-target modeli
- Immutable/tamper-evident log prensibi
- Correlation/request ID ile iz sürülebilirlik

## Gerçek Ürün Geliştirmede Problem

Sık hata: ya çok az loglamak (kanıt yok) ya da aşırı loglamak (gizli veri riski, yüksek maliyet).

## Yaklaşım / Tasarım

`AUDIT_LOGS` için önerilen alanlar:

- audit_id, occurred_at
- actor_user_id, actor_type
- tenant_id
- action_type, target_type, target_id
- outcome (`allow`, `deny`, `error`)
- reason_code
- request_id / correlation_id
- ip_address, device_session_id
- before_snapshot / after_snapshot (gereken yerde)
- metadata (JSON)

Minimum kritik aksiyon listesi:

- login, refresh, logout, logout-all
- `role_permission`, `user_permission`, `common_permission` değişiklikleri
- yüksek riskli domain aksiyonları
- DSAR ve veri dışa aktarım işlemleri

## Sektör Standardı / Best Practice

- `audit log` ile application log'u ayır.
- Action taxonomy ve reason code standardı belirle.
- Deny kararlarını da kaydet.
- Audit verisi üzerinde doğrudan mutation yetkilerini sınırla.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
record_audit(event):
  entry = normalize(event)
  entry.metadata = redact_sensitive(entry.metadata)
  append_only_write('AUDIT_LOGS', entry)
```

## Bizim Notlarımız / Takım Kararları

- `AUDIT_LOGS` payment, sağlık, counseling/PDR ve yetki yönetimi için zorunludur.
- Güvenlik açısından kritik `deny` kararları da kaydedilecektir.
- Bu tasarım [Retention, Anonymization ve DSAR Akışı](./retention-anonymization-dsar) ile birlikte ele alınacaktır.

## Sözlük

- Append-only: Kayıtların güncellenmeyip sadece eklenmesi.
- Correlation ID: Dağıtık işlemlerde iz sürme anahtarı.
- Tamper-evident: Kayıt üzerinde oynama tespit edilebilir tasarım.

## Araştırma Keywordleri

- `audit log schema best practices`
- `append only audit table`
- `sensitive data redaction audit logging`

## Sonuç

İyi tasarlanmış audit sistemi, yalnızca uyumluluk için değil operasyonel güvenlik ve kök neden analizi için de kritik bir altyapıdır.
