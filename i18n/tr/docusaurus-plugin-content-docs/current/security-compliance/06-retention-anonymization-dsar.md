---
title: Retention, Anonymization ve DSAR Akışı
description: DSAR request'ten onay, anonimleştirme ve audit kanıtına uzanan uçtan uca uyumluluk akışı.
sidebar_position: 6
---

## Giriş

`retention policy`, `anonymization` ve `DSAR workflow` ürün geliştirmede en zor uyumluluk alanlarındandır. Hukuki yorum, veri mimarisi ve operasyonel otomasyon birlikte ele alınmalıdır.

## Neden Önemli

Bu alan eksik tasarlanırsa:

- kullanıcı hak talepleri tutarsız yürür,
- yasal süreler kaçırılır,
- alt sistemlerde eski kişisel veri kalır,
- denetimde kanıt üretilemez.

## Temel Kavramlar

- `retention policy`: veri kategorisine göre saklama süresi kuralı.
- `anonymization`: geri döndürülemez kimliksizleştirme.
- `DSAR workflow`: veri sahibi talebi yaşam döngüsü.
- Legal hold: silme/anonimleştirmeyi geçici engelleyen durum.

## Gerçek Ürün Geliştirmede Problem

Sık problemler:

- DSAR'ı ticket süreci gibi ele alıp sistem state'i kurmamak,
- birincil kaydı temizleyip analytics/backup tarafını unutmak,
- yapılan anonimleştirmeyi politika ve gerekçe ile ilişkilendirmemek.

## Yaklaşım / Tasarım

DSAR akış adımları:

1. Talep alımı
2. Kimlik doğrulama
3. Uygunluk kontrolü (legal basis, legal hold, retention sınırları)
4. Onay/red kararı
5. Uygulama (`anonymization` veya silme)
6. `audit log` kaydı ve kapanış bildirimi

Gereken veri katmanı bileşenleri:

- durum makinesi içeren DSAR request tablosu
- async işler için job queue
- sistemler arası yürütme adaptörleri
- her geçişte audit event üretimi

## Sektör Standardı / Best Practice

- Ad hoc script yerine explicit workflow state kullan.
- Onay kararı ile execution worker'ı ayır.
- Geri döndürülemez işlemleri idempotent tasarla.
- Amaç bazlı politika uygula; tek tip silme davranışı kurma.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
on DSAR_REQUEST(subject_id, request_type):
  request = create_request(subject_id, request_type, status='received')
  emit_audit('dsar_received', request.id)

  if not verify_identity(subject_id):
    update_status(request, 'rejected_identity_failed')
    emit_audit('dsar_rejected_identity_failed', request.id)
    stop

  if has_legal_hold(subject_id):
    update_status(request, 'blocked_legal_hold')
    emit_audit('dsar_blocked_legal_hold', request.id)
    stop

  if evaluate_retention_policy(subject_id, request_type) == 'approve_anonymization':
    update_status(request, 'approved')
    enqueue_anonymization_job(request.id)

on ANONYMIZATION_JOB(request_id):
  targets = resolve_subject_data_scope(request_id)
  anonymize_targets(targets)
  update_status(request_id, 'completed')
  emit_audit('dsar_completed', request_id)
```

## Bizim Notlarımız / Takım Kararları

- Hedef akış net: DSAR request -> approval -> anonymization -> audit.
- `retention policy` kuralları kod seviyesinde modellenmelidir.
- Her aşama `audit log` ile izlenebilir olmalıdır.
- Audit detayları için [Audit Log Tasarımı](./audit-log-design) referans alınır.

## Sözlük

- DSAR: Data Subject Access Request.
- Legal hold: yasal nedenli geçici saklama zorunluluğu.
- Idempotent job: tekrar çalışsa da aynı sonucu üreten işlem.

## Araştırma Keywordleri

- `dsar workflow implementation`
- `data anonymization pipeline`
- `retention policy enforcement architecture`

## Sonuç

Uyumluluk odaklı veri yaşam döngüsü, manuel süreçle yönetilemez. Açık workflow, policy uyumu ve audit kanıtı birlikte tasarlanmalıdır.
