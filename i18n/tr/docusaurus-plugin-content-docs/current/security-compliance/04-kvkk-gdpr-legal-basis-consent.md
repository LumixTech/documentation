---
title: KVKK ve GDPR Temelleri - Legal Basis ve Consent
description: Veri işleme amacı odaklı hukuki dayanak modellemesi ve explicit consent sınırları.
sidebar_position: 4
---

## Giriş

Her kişisel veri işleme adımı explicit consent gerektirmez. KVKK/GDPR uyumlu ürün tasarımında asıl ihtiyaç; amaç bazlı hukuki dayanak modelidir.

Bu doküman teknik modelleme içindir, hukuki danışmanlık yerine geçmez.

## Neden Önemli

Yanlış modelleme şunlara yol açar:

- zorunlu ürün akışları gereksizce bloklanır,
- consent her işleme için yanlış kullanılır,
- denetimde karar gerekçesi üretilemez.

## Temel Kavramlar

- Processing purpose: veri işleme amacı.
- Legal basis: işlemenin hukuki dayanağı.
- Consent: dayanak türlerinden yalnızca biri.
- Data minimization: amaç kadar veri işleme.

## Gerçek Ürün Geliştirmede Problem

"Tek consent flag" yaklaşımı, marketing ve operational processing davranışlarını birbirine karıştırır ve ürün akışlarını kırar.

## Yaklaşım / Tasarım

Amaç-dayanak matrisi oluştur:

| Veri İşleme Amacı | Örnek Hukuki Dayanak | Varsayılan Consent Gereksinimi | Not |
| --- | --- | --- | --- |
| Hesap açılışı ve erişim yönetimi | Sözleşmesel gereklilik | Genelde hayır | Çekirdek ürün işlevi |
| Güvenlik/fraud izleme | Meşru menfaat / yasal yükümlülük | Genelde hayır | Orantılılık kontrolü gerekir |
| Pazarlama iletişimi | Consent (bağlama göre) | Çoğunlukla evet | Opt-in/opt-out desteklenmeli |
| Finansal kayıt saklama | Yasal yükümlülük | Hayır | `retention policy` ile bağlanmalı |

## Sektör Standardı / Best Practice

- Purpose registry tut.
- Privacy notice ve consent artifact kayıtlarını ayır.
- Consent'i kapsam-zaman-geri çekme bilgisiyle kaydet.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
can_process(operation):
  purpose = resolve_purpose(operation)
  basis = legal_basis_for(purpose)

  if basis missing:
    return deny

  if basis == 'consent' and not valid_consent(operation.subject, purpose):
    return deny

  if not data_minimization_ok(operation, purpose):
    return deny

  return allow
```

## Bizim Notlarımız / Takım Kararları

- Tek bir global consent flag kullanılmayacak.
- İşleme-amacı ve hukuki-dayanak matrisi ürün akışına bağlanacak.
- Uyum olayları [Audit Log Tasarımı](./audit-log-design) ve [Retention, Anonymization ve DSAR Akışı](./retention-anonymization-dsar) ile entegre olacak.

## Sözlük

- Legal basis: işlemenin hukuki zemini.
- Consent: belirli amaç için açık ve geri alınabilir onay.
- Purpose registry: amaçların merkezi kaydı.

## Araştırma Keywordleri

- `gdpr legal basis vs consent`
- `kvkk hukuki dayanak modelleme`
- `purpose based data processing`

## Sonuç

Uyumlu ürün davranışı için consent ve legal basis ayrımı net olmalıdır. Amaç bazlı model, hem kullanıcı deneyimini hem hukuki savunulabilirliği güçlendirir.
