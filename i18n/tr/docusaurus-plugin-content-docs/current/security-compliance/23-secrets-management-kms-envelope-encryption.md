---
title: "Secrets Management, KMS ve Envelope Encryption"
description: Uygulama secret'ları, DB şifreleri, KMS tabanlı envelope encryption, rotasyon ve erişim kontrolü.
sidebar_position: 7
---

## Giriş

Secrets management; API key, DB şifresi, signing key, encryption key ve servis credential gibi hassas değerlerin saklanması, dağıtılması, döndürülmesi ve denetlenmesi disiplinidir.

Bir secret'ı environment variable içine koymak pratik olabilir; fakat tek başına güvenlik modeli değildir. Üretim sisteminde rotasyon, erişim kontrolü, audit izi, encryption at rest, blast radius kontrolü ve sahiplik tanımı da gerekir.

Bu doküman uygulama secret'ları, database şifreleri ve field encryption key akışını standartlaştırır.

## Neden Önemli

Secret'lar genellikle başka sistemlerin kapısını açtığı için etkisi yüksek risk üretir:

- sızan DB şifresi tenant verisini açığa çıkarabilir,
- uzun ömürlü API key servis yaşam döngüsünden bağımsız kalabilir,
- field encryption key yanlış aktöre açılırsa şifreli kişisel veri okunabilir hale gelir,
- rotasyon planı yoksa incident anında panik operasyonuna dönüşür.

Secrets management yalnızca değerleri saklamak değildir; erişimi niyetli, izlenebilir ve yenilenebilir hale getirmektir.

## Temel Kavramlar

- Secret: uygulamanın çalışması için gereken hassas runtime değer.
- KMS: kriptografik anahtarların oluşturulması, korunması, rotasyonu ve audit'i için kullanılan key management service.
- Envelope encryption: verinin data encryption key ile, bu key'in de KMS tarafından korunan master key ile şifrelenmesi.
- Data encryption key: uygulama verisini veya field değerini doğrudan şifreleyen key.
- Key encryption key: başka bir key'i koruyan KMS-managed key.
- External secret: dedicated secret manager'dan gelip runtime ortama senkronize edilen secret.
- Rotation: secret veya key'in servisi bozmadan planlı şekilde değiştirilmesi.
- Least privilege: her workload'a yalnızca ihtiyacı olan secret'ın verilmesi.

## Gerçek Ürün Geliştirmede Problem

Sık görülen kestirme yol şudur:

```text
DB_PASSWORD değerini ENV içine koy ve devam et
```

Bu sadece process'e değer enjekte etmeyi çözer. Şu soruları cevaplamaz:

- Secret uygulamaya gelmeden önce kim okuyabilir?
- Downtime olmadan rotate edilebilir mi?
- Erişim loglanıyor mu?
- Staging yanlışlıkla production credential kullanabilir mi?
- Field encryption key uygulama config'inden ayrılmış mı?
- Pod, CI job veya developer laptop sızarsa ne olur?

Ürün büyüdükçe secret'lar CI/CD, Kubernetes, local development, worker, cron job ve üçüncü parti entegrasyonlara yayılır. Standart akış yoksa aynı secret çok fazla yerde çoğalır.

## Yaklaşım / Tasarım

### Secret Sınıfları

| Sınıf | Örnekler | Saklama Yönü | Rotasyon Önceliği |
| --- | --- | --- | --- |
| Uygulama config secret'ları | SMTP şifresi, üçüncü parti token | Secret manager, runtime injection | Orta |
| Database credential | app user şifresi, migration user şifresi | Environment bazlı ayrılmış secret manager | Yüksek |
| Signing material | JWT signing key, webhook signing key | KMS/HSM veya güçlü erişim kontrollü secret manager | Yüksek |
| Field encryption key | PII data key, tenant bazlı DEK | KMS ile envelope encryption | Kritik |
| CI/CD credential | deploy token, registry token | CI secret store ve scoped permission | Yüksek |

### Runtime Akışı

```text
Secret Manager / KMS
  -> External Secrets Controller veya runtime injector
  -> Kubernetes Secret veya mounted file
  -> Application process
  -> Platform audit kontrolleri
```

Yüksek riskli key'lerde platform destekliyorsa geniş environment variable kullanımı yerine mounted file veya SDK erişimi tercih edilir.

### Field Encryption Key Akışı

```text
1. Uygulama hassas field şifrelemek ister.
2. KMS veya key service üzerinden data encryption key alır.
3. Plaintext DEK sadece memory'de field değerini şifreler.
4. KMS ile şifrelenmiş DEK encrypted field yanında veya key metadata içinde tutulur.
5. Plaintext DEK mümkün olan en kısa sürede memory'den temizlenir.
6. Decryption için hem encrypted data hem de KMS decrypt yetkisi gerekir.
```

Bu model database compromise ile key compromise riskini ayırır. Database ciphertext ve encrypted key material tutar; KMS erişimi ayrı yönetilir.

### Rotasyon Stratejisi

```text
yeni version oluştur
yeni version'ı okuyabilen consumer'ları deploy et
active version'ı değiştir
hata oranını izle
grace period sonrası eski version'ı emekli et
audit event yaz
```

DB şifresi için mümkünse dual credential veya staged user replacement kullanılır:

```text
app_user_v1 aktif
app_user_v2 oluştur
gerekli privilege'ları ver
app_user_v2 kullanan app'i deploy et
trafik doğrula
app_user_v1 revoke et
app_user_v1 drop et
```

## Sektör Standardı / Best Practice

- Secret'ları Git, image veya statik config içinde değil dedicated secret manager'da tut.
- Master key için KMS, field-level hassas veri için envelope encryption kullan.
- Secret'ları environment, service, tenant hassasiyeti ve operasyon rolüne göre scope'la.
- Runtime secret ile migration/admin credential'ı ayır.
- Secret'ları planlı aralıklarla ve şüpheli sızıntı sonrası hemen rotate et.
- Secret read/decrypt operasyonlarını audit et.
- Encrypted data için key version stratejisi dokümante et.
- Kubernetes Secret verisi için encryption at rest ve mümkünse KMS entegrasyonu kullan.

## Pseudocode / Karar Akışı / Yaşam Döngüsü / Politika Örneği

```text
request_secret(service, secret_name, environment):
  if service secret_name erişimine yetkili değilse:
    deny_and_audit(service, secret_name)

  if environment_mismatch(service, secret_name):
    deny_and_audit(service, secret_name)

  value = secret_manager.read(secret_name, version='active')
  audit_secret_access(service, secret_name, version='active')
  return value
```

Envelope encryption:

```text
encrypt_sensitive_field(tenant_id, field_value):
  dek = kms.generate_data_key(key_id='field-encryption-key')
  ciphertext = encrypt(field_value, dek.plaintext)

  store({
    tenant_id,
    ciphertext,
    encrypted_dek: dek.ciphertext,
    key_version: dek.key_version
  })

  wipe(dek.plaintext)
```

## Bizim Notlarımız / Takım Kararları

- Secret değerleri environment variable ile enjekte edildiği için güvenli kabul edilmemelidir.
- Uygulama secret'ları, database şifreleri ve field encryption key akışları ayrı ele alınmalıdır.
- Rotasyon ve erişim kontrolü tasarımın parçasıdır; sonradan eklenecek operasyon işi değildir.
- Field encryption için envelope encryption tercih edilmelidir.
- Kritik secret erişimleri, rotasyon ve emergency access süreçleri [Audit Log Tasarımı](./audit-log-design) ile ilişkilendirilmelidir.

## Harici Referanslar

- Kubernetes: [Encrypting Confidential Data at Rest](https://kubernetes.io/docs/tasks/administer-cluster/encrypt-data/)

## Sözlük

- Secret: yazılımın ihtiyaç duyduğu gizli runtime değer.
- KMS: kriptografik key yaşam döngüsü ve erişim kontrolü sistemi.
- Envelope encryption: data key'in master key ile korunduğu şifreleme deseni.
- Data encryption key: veriyi doğrudan şifreleyen key.
- Key encryption key: başka bir key'i şifreleyen key.
- Rotation: secret veya key'in kontrollü şekilde değiştirilmesi.
- External secret: secret manager'dan runtime ortama senkronize edilen secret.

## Araştırma Keywordleri

- `secret management kms envelope encryption`
- `key rotation strategy application secrets`
- `external secrets kubernetes secret manager`
- `database password rotation zero downtime`
- `field level encryption envelope encryption`

## Sonuç

Secrets management bir lokasyon değil yaşam döngüsüdür. Üretim tasarımı secret'ın nerede yaşadığını, kimin eriştiğini, nasıl rotate edildiğini, erişimin nasıl audit edildiğini ve encrypted data'nın blast radius büyütmeden nasıl kurtarılacağını açıklamalıdır.
