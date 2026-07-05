---
title: Lisans Yönetimi (JWT Signed .lic)
description: Lisans dosyası tasarımı — JWT (RS256) signed `.lic`. İçerik, online renewal (opsiyonel), offline doğrulama, lisans generator (kapalı kaynak araç), tampering koruması.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

Lumix self-host SaaS modelinde "müşterinin kendi VPS'inde çalışan bir kurulum"u nasıl **sınırlanmış**, **denetlenebilir** ve **süreli** tutarız? Cevap: **JWT (RS256) ile imzalanmış lisans dosyası** (`.lic`). Bu sayfa lisans dosyasının **tasarımını**, **payload alanlarını**, **imzalama + doğrulama mekanizmasını**, **online renewal** (opsiyonel) ve **offline doğrulama** akışlarını, **License Generator** aracını ve **tampering koruması**'nı detaylandırır. Hedef kitle: backend mimar, security mühendisi.

## 1. Bu nedir? (Sıfırdan)

**Yazılım lisansı**, bir kullanıcının (müşterinin) yazılımı **hangi şartlarla** kullanabileceğini tanımlar. Lumix'te:
- Hangi modülleri (academic, finance, file…) açabilir?
- Maksimum kaç tenant?
- Maksimum kaç kullanıcı?
- Lisans hangi tarihe kadar geçerli?
- Hangi feature flag'ler aktif?

Lumix lisansı **JWT (JSON Web Token)** formatında, **RS256** (RSA SHA-256) ile imzalı. Müşteri cluster'ında `.lic` dosyası olarak saklanır; `license-service` (veya identity-service alt-modülü) startup'ta yükler, doğrular, claim'leri sisteme yansıtır.

### Günlük hayattan analoji

Pasaport: ülke sınırı geçerken doğrulanır (imza/chip). Sahte pasaportu görsel olarak ayırt etmek zor ama elektronik doğrulama anında düşer. Pasaport süresi bitince ülkeye giriş yok. Lisans = yazılımın pasaportu.

### Neden self-signed JWT?

Alternatif: lisans server'a online çağrı her startup'ta. Lumix offline-capable müşteriler için **çağrısız** doğrulama zorunlu kıldı. JWT bunun standart formu.

## 2. Hangi problemi çözüyor?

| Acı | Lisans yok | Lisans var |
|---|---|---|
| Müşteri ödeme yapmadı, kurulum devam ediyor | Manuel takip | Lisans expiry → grace period → kilit |
| Müşteri sözleşmeden fazla modül aktive etti | Görünmez | License check → modül başlatılmaz |
| Müşteri 5 tenant alıp 50 açtı | Görünmez | tenants_max → 6. tenant CREATE 402 |
| Self-host müşteri Lumix sunucusunu kapatabilir mi? | Pirate version mümkün | JWT signature → manipülasyon imkansız |
| Müşteri internetsiz çalışıyor (offline kurumlar) | Online check fail | Offline doğrulama yapılabilir |
| Müşteri yenileme yapmadı | Manuel hatırlatma | Lisans 30 gün önce expiry warning |

### Patlamış üretim hikayesi (anti-pattern)

Bir SaaS firması yıllık sözleşme yaptı, kurulum yaptı, ödeme almadı. 8 ay sonra fark edildi; müşteri "biz hâlâ kullanıyoruz, sorun yok" dedi. Compliance ile değil, **technical enforcement** ile lisans gerekiyordu. Lumix bunu baştan kuruyor.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. JWT yapısı

```
header.payload.signature
```

**Header**:
```json
{ "alg": "RS256", "typ": "JWT", "kid": "lumix-license-2026" }
```

**Payload** (örnek):
```json
{
  "iss": "https://license.lumix.io",
  "sub": "omer-okullari",
  "aud": "lumix-installation",
  "iat": 1714521600,
  "nbf": 1714521600,
  "exp": 1745971200,
  "jti": "lic-2026-04-30-omer-okullari-001",
  "version": "1.0",
  "customer": {
    "id": "omer-okullari",
    "display_name": "Ömer Okulları A.Ş.",
    "tax_id": "1234567890",
    "country": "TR"
  },
  "limits": {
    "tenants_max": 5,
    "users_max": 2500,
    "storage_gb_max": 500
  },
  "modules_enabled": [
    "identity", "organization", "academic",
    "finance", "file", "communication"
  ],
  "features": {
    "sso_keycloak": true,
    "payment_provider": true,
    "advanced_reporting": false,
    "mobile_push": true
  },
  "renewal": {
    "online_renewal_url": "https://license.lumix.io/renew",
    "grace_period_days": 30
  },
  "fingerprint": {
    "expected_installation_id": "omer-okullari",
    "expected_region": "tr-istanbul"
  }
}
```

**Signature**: RS256 → private key Lumix License Generator'da; public key her installation'a dağıtılmış.

### 3.2. Anahtar yönetimi

- **Private key**: Lumix License Generator'da; Vault'ta saklanır; sadece license-issuing CI job erişebilir.
- **Public key**: Her Lumix release'ine **gömülü** (immutable). Public key'ler dökümante edilir (`lumix-license-2026.pub`).
- **Key rotation**: yıllık. `kid` (Key ID) header alanı sayesinde bir release birden fazla public key tanır.

### 3.3. Doğrulama akışı (license-service)

```
1. Startup: /etc/lumix/license.lic dosyasını oku
2. Parse JWT → header.payload.signature
3. Header.kid → bilinen public key'ler arasında ara
4. Signature verify (RS256, public key)
5. exp > now() ?
6. nbf <= now() ?
7. iss == "https://license.lumix.io" ?
8. aud == "lumix-installation" ?
9. customer.id == ${LUMIX_INSTALLATION_ID} env var ?
10. fingerprint.expected_installation_id == ... ?
11. Eğer tüm check OK:
       Cache claim'leri Redis'e (TTL = exp - now)
       Modul-level enforcement aktif
12. Eğer bir check FAIL:
       Log + alert + grace mode
       Modules read-only veya kapalı
```

### 3.4. Modül enforcement

Her microservice startup'ta license-service'ten **modül enabled** sorgusu yapar:

```http
GET /api/v1/license/modules
→ ["identity", "academic", "finance", ...]
```

Eğer servisin kendisi enabled değilse: startup'ta liveness probe fail → pod restart → eventual `CrashLoopBackOff`. Veya servis kendi içinde "read-only mode" + 503 endpoint.

### 3.5. Limit enforcement

`tenants_max`, `users_max` gibi limitler:
- Yeni tenant create endpoint'i: önce count yap, limit'i aşarsa 402 Payment Required + RFC 7807.
- Periodic background job (Temporal scheduled): aktif tenant sayısı limit'i aştıysa alert + "soft-warn" mode (yeni create yasak ama mevcut çalışır).

### 3.6. Online renewal (opsiyonel)

Müşteri internet erişimi varsa:
- License-service her 24 saatte License Generator'a `GET /v1/licenses/{customer-id}/latest` çağırır.
- Yeni bir JWT varsa indirir, signature verify, dosyayı yeniler.
- Müşteri yeni sözleşme imzaladığında License Generator yeni JWT üretir, müşteri otomatik alır.

Offline müşteri için: manuel `.lic` upload (Internal Admin Panel ile).

### 3.7. Grace period

Lisans expire olduğunda:
- **Grace period (30 gün)**: read-only mode (login OK, yeni create yasak).
- **Grace sonrası**: tüm endpoint 402 + login bile yasak. Sadece license upload endpoint açık.

### 3.8. Tampering koruması

- **Signature**: payload değiştirilemez (public key ile verify).
- **`jti`**: her lisansa unique ID; revocation list (CRL benzeri) tutulur. License Generator'da `secret/lumix/revoked-licenses` Vault path.
- **`fingerprint.expected_installation_id`**: lisansı başka müşteriye kopyalamak işe yaramaz.
- **Cluster fingerprint** (opsiyonel future): cluster's etcd UUID veya RKE2 cluster ID lisansa gömülür. Tek cluster'da çalışır.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. License Generator (kapalı kaynak araç)

Lumix sağlayıcısının çalıştırdığı **internal service**. Sorumlulukları:
- Yeni lisans üretmek (JWT RS256 sign).
- Mevcut lisansı yenilemek.
- Lisansı revoke etmek (revocation list).
- Audit log (kim hangi lisansı üretti).
- Müşteri portal API (Internal Admin Panel ile entegre).

Stack: Java 25 + Spring Boot. Vault'tan private key okur (in-memory cache). Audit log audit-service'e Kafka ile push.

```
gitlab.lumix.io/internal/license-generator/   (PRIVATE repo)
├── src/main/java/...
├── helm/license-generator/
└── README.md
```

### 4.2. Müşteri tarafında `.lic` yaşam döngüsü

```
1. Müşteri profile'ı oluşturulduğunda → License Generator çağrılır → JWT üretilir
2. JWT dosya halinde Vault'a yazılır: secret/lumix/{cid}/license/current
3. ESO (External Secrets Operator) o Vault path'i K8s Secret'a çeker
4. license-service Secret'ı mount eder
5. Yenileme:
   - 30 gün önceden alert (license-service Prometheus metric)
   - Lumix sales takip
   - Yeni JWT üretildiğinde Vault'a yazılır → ESO sync → license-service reload
```

### 4.3. License-service mimarisi

```
┌──────────────────────────────────────────┐
│  license-service Pod                     │
│  /etc/lumix/license.lic (Secret mount)   │
│                                          │
│  Startup:                                │
│   1. Read .lic                           │
│   2. Verify JWT (built-in public keys)   │
│   3. Cache claims to Redis (TTL=exp-now) │
│                                          │
│  REST API:                               │
│   GET /api/v1/license/status             │
│   GET /api/v1/license/modules            │
│   GET /api/v1/license/limits             │
│   POST /api/v1/license/upload (admin)    │
│                                          │
│  Periodic:                               │
│   - Re-verify every 1 hour               │
│   - Online renewal check (if enabled)    │
│   - Emit expiry metric (Prometheus)      │
└──────────────────────────────────────────┘
```

Public key listesi (`/etc/lumix/license-public-keys/`):
```
lumix-license-2025.pub
lumix-license-2026.pub
lumix-license-2027.pub
```

JWT header'daki `kid` ile eşleşen key kullanılır.

### 4.4. Modül enforcement örneği

`academic-service` startup:

```java
@SpringBootApplication
@RestController
public class AcademicServiceApplication implements ApplicationListener<ContextRefreshedEvent> {

    @Autowired LicenseClient licenseClient;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        var modules = licenseClient.getEnabledModules();
        if (!modules.contains("academic")) {
            log.error("Module 'academic' not enabled by license. Shutting down.");
            System.exit(78);  // EX_CONFIG
        }
    }
}
```

K8s seviyesinde: chart `values.yaml`'da `modules` listesinden Deployment enabled/disabled.

### 4.5. tenants_max enforcement (organization-service)

```java
public Tenant createTenant(CreateTenantCommand cmd) {
    var limits = licenseClient.getLimits();
    var currentCount = tenantRepository.countActive();
    if (currentCount >= limits.tenantsMax()) {
        throw new LicenseLimitExceededException(
            "tenants_max=%d exceeded".formatted(limits.tenantsMax())
        );
    }
    // ... create tenant
}
```

REST adapter `LicenseLimitExceededException` → 402 Payment Required + RFC 7807 problem detail.

### 4.6. License renewal akışı

```
Sales → "Müşteri X yeniledi"
   │
   ▼
Internal Admin Panel → /api/v1/licenses/renew
   │
   ▼
License Generator:
  1. Önceki JWT'yi jti'sine ekle revocation list
  2. Yeni JWT üret (yeni exp, yeni jti)
  3. Vault'a yaz: secret/lumix/{cid}/license/current
  4. Audit event publish (Kafka)
   │
   ▼
ESO müşteri cluster'ında Vault değişimini fark eder
   │
   ▼
K8s Secret reload
   │
   ▼
license-service file watch → reload → cache yenile
   │
   ▼
Tüm servisler yeni claim'lere göre çalışır
```

### 4.7. Offline müşteri akışı

Müşteri ay sonu Lumix'e bilgi gönderir (e-posta, telefon). Lumix:
1. License Generator yeni JWT üretir.
2. JWT'yi USB veya secure file transfer ile müşteriye gönderir.
3. Müşteri DevOps Internal Admin Panel'den manuel upload → license-service `/upload` endpoint → file persist → reload.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Online activation server only** | Offline müşteri çalışamaz. |
| **License server polling (her startup'ta)** | Network down = downtime. |
| **Sertifika tabanlı (X.509)** | JWT daha basit + JSON claim flexible. |
| **Hardware key (HSM/USB)** | Müşteri operasyonel yük; cluster otomasyonu zor. |
| **Hash check** | Tampering kolay (replay). |
| **No license, sözleşme bazlı** | Technical enforcement yok, sürekli güven sorunu. |

### Kabul ettiğimiz trade-off'lar

- **Public key compromise senaryosu**: bir release public key'i sızdı; saldırgan public key'i ne yapacak? Doğrulayabilir ama yeni JWT imzalayamaz (private key gerek). Trade-off OK.
- **JWT lokal değiştirilemez ama silinebilir**: müşteri `.lic` dosyasını silerse license-service başlatılamaz → tüm sistem down. **Bu istenen davranış**.
- **Müşteri saat değiştirebilir**: `exp` saate bağlı. Müşteri clock skew yaparsa lisansı uzatır. **Cluster içinde NTP zorunlu** + license-service kendi clock'unu hash ile periyodik doğrular (gelişmiş tampering koruması).
- **Renewal manuel adımları gerektirir**: tam otomatik değil; ama offline destek için kabul.

### Tekrar değerlendirme tetikleyicileri

- Cluster fingerprint zorunlu olursa (lisansı başka cluster'a kopyalamayı engellemek için): `etcd UUID` veya `cluster-id` lisansa eklenmeli.
- Çok büyük müşteri sayısı için Self-Service Renewal Portal.

## 6. Pratik örnek

### 6.1. License Generator API (özet)

```http
POST /api/v1/licenses
Authorization: Bearer <internal-admin-token>
Content-Type: application/json

{
  "customer_id": "omer-okullari",
  "display_name": "Ömer Okulları A.Ş.",
  "tenants_max": 5,
  "users_max": 2500,
  "modules_enabled": ["identity","organization","academic","finance","file","communication"],
  "features": { "mobile_push": true, "sso_keycloak": false },
  "valid_until": "2027-04-30T23:59:59Z",
  "renewal": { "online": true }
}

→ 201 Created
{
  "license_id": "lic-2026-04-30-omer-okullari-001",
  "license_jwt": "eyJhbGciOiJSUzI1NiIsInR5cCI6...",
  "exp": "2027-04-30T23:59:59Z"
}
```

### 6.2. Java JWT üretimi (License Generator özet)

```java
@Service
public class LicenseIssuer {

    @Autowired private RsaKeyService keyService;   // Vault'tan private key

    public String issueLicense(LicenseRequest req) {
        var now = Instant.now();
        var exp = req.validUntil().toInstant();
        var jti = "lic-" + now.toLocalDate() + "-" + req.customerId() + "-" + UUID.randomUUID();

        var claims = JwtClaimsSet.builder()
            .issuer("https://license.lumix.io")
            .subject(req.customerId())
            .audience(List.of("lumix-installation"))
            .issuedAt(now)
            .notBefore(now)
            .expiresAt(exp)
            .id(jti)
            .claim("customer", req.customer())
            .claim("limits", req.limits())
            .claim("modules_enabled", req.modulesEnabled())
            .claim("features", req.features())
            .claim("renewal", req.renewal())
            .claim("fingerprint", Map.of(
                "expected_installation_id", req.customerId(),
                "expected_region", req.region()))
            .build();

        var header = JwsHeader.with(SignatureAlgorithm.RS256)
            .keyId(keyService.currentKid())   // "lumix-license-2026"
            .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
```

### 6.3. license-service doğrulama (Java)

```java
@Service
public class LicenseValidator {

    private final Map<String, RSAPublicKey> publicKeys = loadFromFilesystem("/etc/lumix/license-public-keys/");

    public LicenseClaims validate(String jwtString) {
        var jwt = SignedJWT.parse(jwtString);
        var kid = jwt.getHeader().getKeyID();
        var publicKey = publicKeys.get(kid);
        if (publicKey == null) {
            throw new LicenseInvalidException("unknown kid: " + kid);
        }

        var verifier = new RSASSAVerifier(publicKey);
        if (!jwt.verify(verifier)) {
            throw new LicenseInvalidException("signature mismatch");
        }

        var claims = jwt.getJWTClaimsSet();
        var now = Date.from(Instant.now());
        if (claims.getExpirationTime().before(now)) {
            throw new LicenseExpiredException("expired at " + claims.getExpirationTime());
        }
        if (claims.getNotBeforeTime().after(now)) {
            throw new LicenseInvalidException("not yet valid");
        }
        if (!"https://license.lumix.io".equals(claims.getIssuer())) {
            throw new LicenseInvalidException("bad iss");
        }
        if (!claims.getAudience().contains("lumix-installation")) {
            throw new LicenseInvalidException("bad aud");
        }
        var fingerprint = (Map<String, Object>) claims.getClaim("fingerprint");
        if (!installationId.equals(fingerprint.get("expected_installation_id"))) {
            throw new LicenseInvalidException("installation_id mismatch");
        }

        return mapClaims(claims);
    }
}
```

### 6.4. license.lic dosyası (mount edilen Secret)

```yaml
# K8s Secret (ESO ile Vault'tan)
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: lumix-license
  namespace: lumix-app
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: vault-backend
    kind: ClusterSecretStore
  target:
    name: lumix-license
  data:
    - secretKey: license.lic
      remoteRef:
        key: secret/data/lumix/omer-okullari/license/current
        property: jwt
```

Pod mount:
```yaml
volumes:
  - name: license
    secret:
      secretName: lumix-license
volumeMounts:
  - name: license
    mountPath: /etc/lumix
    readOnly: true
```

### 6.5. Internal Admin Panel akışı

```
Internal Admin Panel UI
  ↓
  "Müşteri X için yeni 12 aylık lisans üret"
  ↓
License Generator API çağrısı
  ↓
JWT üretilir + Vault'a yazılır
  ↓
ESO sync (max 1h)
  ↓
license-service file change watch
  ↓
Reload + claims güncellenir
  ↓
Internal Admin Panel: "Renewed, exp: 2027-04-30"
```

### 6.6. Hızlı manuel doğrulama

```bash
# JWT dosyasını al
LIC=$(cat license.lic)

# Decode payload (jwt-cli veya manuel)
echo $LIC | cut -d. -f2 | base64 -d 2>/dev/null | jq .

# Signature verify (openssl)
echo -n "$(echo $LIC | cut -d. -f1).$(echo $LIC | cut -d. -f2)" | \
  openssl dgst -sha256 -verify lumix-license-2026.pub \
  -signature <(echo $LIC | cut -d. -f3 | base64 -d 2>/dev/null)
```

## 7. Dikkat edilecek tuzaklar

- **Private key'in repository'ye sızması**: tüm güvenlik biter. Vault + RBAC + audit zorunlu; private key sadece License Generator pod'unun runtime memory'sinde.
- **Public key'i runtime değişir yapmak**: müşteri tarafında public key sızdırıldı diye değiştirsek bile o release'in tanıdığı key'ler değişmiyor. Public key'ler **immutable** (release-time).
- **Clock skew**: müşteri saatleri uydurabilir. license-service ek olarak HTTP `Date` header'larından, NTP'den ve cluster timestamp'lerinden cross-check.
- **`.lic` dosyasını rebuild image'a gömmek**: 1 müşteri için custom image = operasyonel kabus. License Secret'tan mount, image generic.
- **Revocation list'in cluster'da bilinmemesi**: License Generator revoke etti ama müşteri cluster'ı bilmiyor. Solution: license-service periyodik online check (online ise) + JWT içine `revocable_url` field, license-service o URL'i poll eder.
- **Grace period sonrası tam kilit yerine soft warning'le bırakmak**: müşteri ödeme yapmadan sonsuza kadar kullanır. Lumix kuralı: grace + lock.
- **Sadece tek release public key**: rotation impossible. **Çoklu kid** zorunlu.
- **JWT'nin Redis'e cache'lenmemesi**: her request'te file read + verify = CPU yükü. Redis cache TTL=exp-now.
- **`fingerprint` field'ı eklemeyi unutmak**: lisans başka cluster'a kopyalanırsa çalışır → revenue kaybı.
- **Modüller listesinin sadece adı**: versiyon bilgisi yok. Future-proof: `{"name":"academic", "min_version":"1.0"}` formatı.
- **Online renewal'da SSL pinning eksik**: MITM ile sahte lisans yüklenebilir. license-service trust store sıkı.
- **Public key rotation testi yok**: production'a yeni kid çıkarken eskisi düşürülürse müşteri lisansı geçersiz. Phased rotation: yeni kid eklendiğinde eskisi 6 ay daha geçerli.

## 8. Diğer konularla ilişkisi

- [Customer Onboarding Pipeline](./03-customer-onboarding-pipeline.md) — license seed adımı
- [Authentication](../authentication-authorization) — JWT (RS256) aynı algoritma; lisans için **ayrı key**
- [Vault](../security-compliance) — private key ve license JWT storage
- [External Secrets Operator](../security-compliance) — Vault'tan K8s Secret sync
- [Internal Admin Panel](../admin-panels) — license üretim/renew UI
- [Audit Log](../security-compliance) — license üretim/revoke event'leri
- [Compliance](../security-compliance) — KVKK + lisans şartları ortak

## 9. Daha derine inmek için

- RFC 7519 (JSON Web Token)
- RFC 7515 (JSON Web Signature)
- "OAuth 2 in Action" — Justin Richer (JWT bölümü)
- jwt.io debugger
- HashiCorp Vault PKI engine documentation
- Search keyword'leri: *"jwt rs256 license file design"*, *"software licensing online offline activation"*, *"jwt key rotation kid header"*, *"jwt revocation list"*

## 10. Sözlük

- **License (`.lic`) file**: JWT signed lisans dosyası.
- **JWT (JSON Web Token)**: Self-contained, imzalı claim taşıyıcısı (RFC 7519).
- **RS256**: RSA SHA-256 signature algoritması.
- **Header / Payload / Signature**: JWT'nin 3 parçası.
- **`kid` (Key ID)**: Header alanı; hangi public key ile verify edileceğini gösterir.
- **`iss` / `sub` / `aud` / `exp` / `nbf` / `iat` / `jti`**: Standard JWT claim'leri.
- **`fingerprint`**: Lisans'ın hangi installation'a ait olduğunu sabitleme.
- **License Generator**: Lumix'in private bir aracı; yeni JWT üretir, imzalar.
- **license-service**: Müşteri cluster'ında çalışan, .lic doğrulayan + claim'leri sunan servis.
- **Grace period**: Lisans expire sonrası kabul edilen tampon süre.
- **Revocation list**: İptal edilen lisansların jti listesi.
- **Public key rotation**: Periyodik anahtar değişimi; eski ve yeni anahtarlar birlikte geçerli (phased).
- **Offline doğrulama**: İnternet bağlantısı olmadan lisansı verify edebilmek.
- **Online renewal**: İnternet bağlantısıyla otomatik lisans yenileme.
