---
title: "Threat Modeling ve Security Regression Checklist"
description: Login, chat ve payment flow'ları için STRIDE odaklı mini threat model, abuse case ve regression checklist.
sidebar_position: 8
---

## Giriş

Threat modeling, security risklerini kod veya production incident haline gelmeden önce bulmak için kullanılan yapılandırılmış yaklaşımdır.

Amaç kusursuz security dokümanı üretmek değildir. Amaç abuse case'leri erken görünür kılmak ve architecture, permission, logging ve test tasarımının buna cevap vermesini sağlamaktır.

Bu doküman login, chat ve payment flow'ları için mini threat model tanımlar.

## Neden Önemli

Ciddi security problemlerinin çoğu design problemidir:

- yanlış actor workflow'a erişebilir,
- tenant boundary tutarlı enforce edilmez,
- hassas aksiyon audit edilmez,
- replay veya brute-force düşünülmez,
- payment callback fazla güvenilir kabul edilir,
- chat attachment conversation dışına sızar.

Security regression check'leri düzeltilen risklerin geri gelmesini engeller.

## Temel Kavramlar

- Threat model: neyin yanlış gidebileceğini ve riskin nasıl azaltılacağını analiz eden yapı.
- Abuse case: beklenen davranışı kötüye kullanan attacker-oriented senaryo.
- STRIDE: spoofing, tampering, repudiation, information disclosure, denial of service ve elevation of privilege prompt'ları.
- Trust boundary: verinin actor, system veya privilege zone değiştirdiği sınır.
- Security regression: daha önce kontrol altına alınmış security davranışının tekrar bozulması.
- Mitigation: risk olasılığı veya etkisini azaltan kontrol.

## Gerçek Ürün Geliştirmede Problem

Security çoğu zaman implementation sonrası review edilir; bu aşamada design değişimi daha pahalıdır.

Threat modeling akış hala esnekken yapılmalıdır:

```text
use case design
  -> trust boundaries
  -> abuse cases
  -> mitigations
  -> test/audit checklist
```

## Yaklaşım / Tasarım

### Login Mini Threat Model

| STRIDE Alanı | Abuse Case | Mitigation |
| --- | --- | --- |
| Spoofing | Attacker stolen refresh token kullanır | refresh token rotation, device session tracking, revoke tokens |
| Tampering | Client tenant/user context değiştirir | context'i authenticated session'dan üret, untrusted tenant header'ı kabul etme |
| Repudiation | User login/logout yaptığını inkar eder | login, refresh, logout, logout-all audit |
| Information disclosure | Error message account varlığını açığa çıkarır | generic login error, rate-limited response |
| Denial of service | Brute-force login attempt | rate limit, lockout policy, anomaly detection |
| Elevation of privilege | Token yanlış role/permission claim içerir | server-side permission check, short-lived access token |

Security regression checklist:

- failed login rate-limited,
- refresh token rotation eski token'ı geçersiz kılar,
- logout current device session revoke eder,
- logout-all tüm device session'ları revoke eder,
- tenant context request header'dan körlemesine kabul edilmez,
- authentication event'leri `audit log` içine yazılır.

### Chat Mini Threat Model

| STRIDE Alanı | Abuse Case | Mitigation |
| --- | --- | --- |
| Spoofing | User başka user adına mesaj atar | sender yalnızca session'dan türetilir |
| Tampering | User conversation_id değiştirerek başka tenant'a erişir | tenant ve participant check |
| Repudiation | User mesaj gönderdiğini inkar eder | immutable message metadata ve hassas aksiyon audit'i |
| Information disclosure | Attachment URL non-participant'a sızar | short-lived pre-signed URL, URL öncesi authorization |
| Denial of service | Spam veya büyük attachment flood | rate limit, size limit, moderation rules |
| Elevation of privilege | Parent teacher-only conversation'a erişir | role ve participant authorization |

Security regression checklist:

- sender ID server-derived,
- her send/read için conversation membership kontrol edilir,
- query'lerde `tenant-id` enforce edilir,
- attachment download URL için authorization gerekir,
- deleted veya blocked user mesaj atmaya devam edemez,
- şüpheli message volume observable olur.

### Payment Mini Threat Model

| STRIDE Alanı | Abuse Case | Mitigation |
| --- | --- | --- |
| Spoofing | Fake provider callback payment successful yapar | provider signature verification |
| Tampering | Amount veya currency client tarafından değiştirilir | server-side amount calculation |
| Repudiation | Admin/provider aksiyonu yeniden oluşturulamaz | payment attempt ve status change audit |
| Information disclosure | Payment data log'a düşer | redaction, structured logging policy |
| Denial of service | Repeated payment attempt provider'ı yorar | idempotency key, rate limiting |
| Elevation of privilege | Yetkisiz user refund yapar | RBAC + ABAC permission check |

Security regression checklist:

- payment amount yalnızca client'tan güvenilmez,
- provider callback signature doğrulanır,
- callback processing idempotent yapılır,
- status transition state machine'e uyar,
- payment action audit loglanır,
- hassas provider payload field'ları redact edilir.

## Sektör Standardı / Best Practice

- Kritik flow'ları implementation öncesi threat modelle.
- STRIDE'ı bürokrasi değil prompt olarak kullan.
- Trust boundary'leri açıkça belirle.
- Mitigation'ları test, audit kuralı ve monitoring check'e dönüştür.
- Architecture değiştikçe threat model'i tekrar ele al.
- Security regression checklist'i use case'e yakın tut.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
threat_model(use_case):
  describe_assets_and_actors()
  draw_data_flow()
  identify_trust_boundaries()
  enumerate_stride_abuse_cases()
  define_mitigations()
  create_security_regression_checklist()
  map_high_risk_items_to_tests_and_audit_logs()
```

## Bizim Notlarımız / Takım Kararları

- Login, chat ve payment ilk zorunlu mini threat model'lerdir.
- Security riskleri yalnızca code review'da değil design seviyesinde bulunmalıdır.
- Regression checklist item'ları mümkün olduğunca test veya review gate'e dönüşmelidir.
- Payment, permission ve hassas chat aksiyonları [Audit Log Tasarımı](./audit-log-design) ile bağlanmalıdır.

## Harici Referanslar

- OWASP: [Threat Modeling Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Threat_Modeling_Cheat_Sheet.html)

## Sözlük

- Threat model: yapılandırılmış security risk analizi.
- Abuse case: kötüye kullanım senaryosu.
- STRIDE: security threat kategorileri için prompt seti.
- Trust boundary: güven varsayımlarının değiştiği sınır.
- Mitigation: riski azaltan kontrol.
- Security regression: kontrol edilmiş zafiyetin geri dönmesi.

## Araştırma Keywordleri

- `threat modeling stride abuse cases`
- `security regression checklist`
- `login threat model refresh token`
- `chat application threat model attachments`
- `payment callback signature idempotency threat model`

## Sonuç

Threat modeling security'yi tasarım değişebilirken somutlaştırır. Login, chat ve payment mini threat model'leri permission, audit log, test ve release regression check'lerine doğrudan beslenmelidir.
