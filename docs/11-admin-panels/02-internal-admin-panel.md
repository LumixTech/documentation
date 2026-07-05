---
title: Internal Admin Panel (Lumix Ekibi)
description: Lumix Internal Admin Panel — installation lifecycle, lisans yönetimi, destek dashboard, audit query, billing. Ayrı app, ayrı domain.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

**Internal Admin Panel**, Lumix ekibinin (yani sen, sağlayıcı) kullandığı yönetim panelidir. Müşteri görmez. Bu sayfada şunlar açıklanır:

- Internal Admin Panel'in amacı ve kim kullanır
- Customer Admin'den farkı
- Ana modüller (installation lifecycle, lisans, destek, audit, billing)
- Ayrı app + ayrı domain kararı
- Cross-installation veri çekme
- Lisans (.lic) üretme akışı
- Destek dashboard (per-installation health)
- Güvenlik (extra strict — internal employee için)

Bu sayfa **operasyonel runbook**'un giriş kapısıdır.

## 1. Internal Admin Panel nedir? (Sıfırdan)

Lumix bir SaaS sağlayıcısıdır. Müşterileri var. Her müşterinin kendi installation'ı var. Senin (sağlayıcı) **tüm installation'ları kuş bakışı görmen ve yönetmen** gerek:

- Yeni müşteri (installation) ekle
- Müşteri aboneliğini yenile, lisansı üret
- Bir installation'da problem mi var? Health durumu, alert
- Destek talebine bakarken o müşterinin profilini gör
- Audit log query'leri kros-installation (sadece bir installation'ın değil)
- Aylık/yıllık fatura çıkar

### Günlük hayattan analoji

Bir telefon operatörü düşün:

- **Müşteriler (Installation'lar)** = ev/iş aboneleri
- **Customer Admin** = her abonenin kendi modem panelinden ev WiFi ayarı
- **Internal Admin** = operatörün **NOC (Network Operations Center)**: tüm aboneleri tek ekranda görüyor, hat aç-kapa yapıyor, kapasite ölçüyor, alarm dinliyor

### Lumix terminolojisinde

```
Lumix Sağlayıcı (sen)
  ├── Installation 1 = "Ömer Okulları"
  │     └── Customer Admin: müşterinin kendi yöneticisi
  ├── Installation 2 = "X Eğitim Vakfı"
  ├── Installation 3 = "Y Üniversitesi"
  └── ...

  Internal Admin Panel = Lumix ekibinin tüm 1-N installation'a kuş bakışı bakabildiği panel
```

## 2. Hangi problemi çözüyor?

Internal Admin Panel olmazsa:

- Yeni müşteri eklemek için 10 servisi tek tek konfigüre et (yorucu, hataya açık)
- Müşteri aradığında: "Hangi versiyondasınız? Lisans ne zaman bitiyor? Son hata logunuz ne?" sorularını her seferinde elle bul
- Lisans üretme manuel script + GPG signing dans
- Aylık fatura döngüsü manuel raporlama

Panel ile:

- **Installation lifecycle wizard**: 5 tıkla yeni müşteri (Terraform + Ansible + ArgoCD orchestrate)
- **Per-installation dashboard**: versiyon, health, son hatalar, bağlı kullanıcı sayısı
- **Lisans tek butonla**: yeni `.lic` üret, GPG imzala, müşteriye gönder
- **Cross-installation audit**: "Geçen ay tüm müşterilerin login fail rate'i?"
- **Billing automation**: faturalar otomatik

## 3. Nasıl çözüyor? (Mekanizma)

### 3.1. Mimari karar — AYRI app, AYRI domain

Customer Admin Panel müşterinin gördüğü web app'in parçasıydı. Internal Admin Panel **AYRI uygulama**:

- **Ayrı git repo / monorepo paketi**: `apps/internal-admin/`
- **Ayrı domain**: `admin.lumix.io` (müşteri domain'leri `app.{customer}.lumix.io`)
- **Ayrı backend endpoint prefix**: `/api/internal/v1/*` (ayrı Kong route)
- **Ayrı authentication realm**: Lumix çalışan SSO (Keycloak / Google Workspace) + 2FA mecburi
- **Network**: Internal admin panel sadece **Lumix office VPN** veya allowlisted IP'den ulaşılabilir

### 3.2. Niye ayrı?

| Sebep | Açıklama |
|---|---|
| **Patlama yarıçapı** | Internal admin DB'ye doğrudan erişebilir, müşteri verisini görür — UI compromise olmamalı |
| **Auth zorluğu** | Customer login = email/şifre + customer-admin permission. Internal = SSO + 2FA + IP whitelist. Aynı app'te karıştırmak risk |
| **Deployment cadence** | Internal panel daha agresif (haftada birkaç değişiklik); customer app conservative |
| **Branding** | Customer app müşteri brand'i taşıyabilir; internal kendine has |
| **Audit ayrımı** | Internal action'lar ayrı audit stream (super-audit) |

### 3.3. Backend katmanı

```
Lumix Sağlayıcı altyapısı (SaaS tarafı)
├── internal-admin-service (microservice)
│   ├── REST: /api/internal/v1/*
│   ├── Aggregator: her installation'a sync gRPC çağrısı (read-only)
│   ├── Lisans üreticisi (RSA private key Vault'tan)
│   └── Provisioning orchestration (Terraform + Ansible + ArgoCD API)
├── billing-service (per-installation invoice)
├── support-service (ticket integration)
└── kros-installation log aggregator (Loki query)
```

### 3.4. Cross-installation data çekme

Bir installation'a query atmak için Lumix sağlayıcı altyapısı **read-only credentials** ile bağlanır:

```
Internal Admin UI → POST /api/internal/v1/installations/{id}/health
  → internal-admin-service:
     → o installation'ın K8s API'sini Rancher token ile çağırır
     → veya o installation'ın `system-info` endpoint'ine (mTLS) çağrı atar
     → response: { version, podCount, dbDiskUsage, lastError, ... }
```

Müşteri verisine erişim için **scope-restricted endpoint** + **müşteri onayı** zorunlu (KVKK). Müşteri verisi normalde Internal Admin'in elinden geçmez — sadece anonimleştirilmiş telemetry.

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. Kararlar

| Konu | Karar |
|---|---|
| App | **Ayrı `apps/internal-admin`** (monorepo içinde) |
| Domain | **`admin.lumix.io`** |
| Auth | **Keycloak (Lumix internal realm) + 2FA + IP allowlist** |
| Stack | **React + Redux Toolkit + RTK Query** (aynı `@lumix/core`'dan faydalanır) |
| Backend prefix | **`/api/internal/v1/*`** + ayrı Kong service |
| Audit | **Super-audit stream** — ayrı Kafka topic |
| Erişim | Sadece Lumix office IP veya VPN |
| Granular permission | `installation:read`, `installation:provision`, `license:issue`, `support:respond` vb. |
| Read-only default | Çoğu işlem read-only; write için ek confirmation modal |
| Mobile build | YOK (web-only) |

### 4.2. Ana modüller

| Modül | Path | Özellik |
|---|---|---|
| **Installation Lifecycle** | `/installations` | Liste; yeni müşteri provisioning wizard; suspend / resume / decommission |
| **License Management** | `/licenses` | Aktif lisanslar, expiry takvimi, yenileme, `.lic` üret |
| **Support Dashboard** | `/support` | Per-installation health, son alert, performance metrics, "remote console" |
| **Cross-Installation Audit** | `/audit` | Tüm installation'larda audit query (sınırlı: aggregate, anonimize) |
| **Billing** | `/billing` | Aylık fatura listeleri, gönderilmiş/ödenmiş durumu, manual override |
| **Customer Profile** | `/installations/{id}` | Detay: tier, kişi, sözleşme, log link, lisans link |
| **Operations** | `/ops` | Cluster events, ArgoCD sync status, deployment rollback |

### 4.3. RTK Query endpoint örneği

```ts
// apps/internal-admin/src/features/installations/api.ts
import { lumixApi } from '@lumix/core/shared/api';

export const installationApi = lumixApi.injectEndpoints({
  endpoints: (build) => ({
    listInstallations: build.query<Installation[], { status?: string; tier?: string }>({
      query: (params) => ({ url: '/api/internal/v1/installations', params }),
      providesTags: [{ type: 'Installation' as const, id: 'LIST' }],
    }),
    getInstallation: build.query<InstallationDetail, { id: string }>({
      query: ({ id }) => `/api/internal/v1/installations/${id}`,
      providesTags: (_r, _e, arg) => [{ type: 'Installation' as const, id: arg.id }],
    }),
    getInstallationHealth: build.query<HealthSnapshot, { id: string }>({
      query: ({ id }) => `/api/internal/v1/installations/${id}/health`,
      keepUnusedDataFor: 30, // sık yenile
    }),
    provisionInstallation: build.mutation<{ id: string }, ProvisionRequest>({
      query: (body) => ({
        url: '/api/internal/v1/installations',
        method: 'POST',
        body,
      }),
      invalidatesTags: [{ type: 'Installation' as const, id: 'LIST' }],
    }),
    suspendInstallation: build.mutation<void, { id: string; reason: string }>({
      query: ({ id, reason }) => ({
        url: `/api/internal/v1/installations/${id}/suspend`,
        method: 'POST',
        body: { reason },
      }),
      invalidatesTags: (_r, _e, arg) => [{ type: 'Installation' as const, id: arg.id }],
    }),
    issueLicense: build.mutation<{ licenseFile: string }, IssueLicenseRequest>({
      query: (body) => ({
        url: '/api/internal/v1/licenses/issue',
        method: 'POST',
        body,
      }),
    }),
  }),
});
```

### 4.4. Provisioning wizard (yeni müşteri ekle)

```tsx
// apps/internal-admin/src/pages/installations/NewInstallationWizard.tsx
import { useState } from 'react';
import { useProvisionInstallationMutation } from '@/features/installations/api';

type Step = 1 | 2 | 3 | 4;

export function NewInstallationWizard() {
  const [step, setStep] = useState<Step>(1);
  const [data, setData] = useState<ProvisionRequest>({
    customerName: '',
    contactEmail: '',
    tier: 'standard',
    region: 'tr-central',
    initialTenantName: '',
    modulesEnabled: ['attendance', 'messages'],
    licenseValidUntil: '',
  });
  const [provision, { isLoading, data: result }] = useProvisionInstallationMutation();

  const handleProvision = async () => {
    await provision(data).unwrap();
    setStep(4);
  };

  return (
    <div>
      <h1>Yeni Müşteri</h1>
      {step === 1 && <CustomerInfoStep data={data} onChange={setData} onNext={() => setStep(2)} />}
      {step === 2 && <TierAndModulesStep data={data} onChange={setData} onNext={() => setStep(3)} />}
      {step === 3 && (
        <ReviewStep data={data} onConfirm={handleProvision} isLoading={isLoading} />
      )}
      {step === 4 && result && (
        <SuccessStep installationId={result.id} />
      )}
    </div>
  );
}
```

### 4.5. Lisans üretme

```tsx
// apps/internal-admin/src/features/licenses/ui/IssueLicenseForm.tsx
import { useIssueLicenseMutation } from '../api';

export function IssueLicenseForm({ installationId }: Props) {
  const [issue, { isLoading, data }] = useIssueLicenseMutation();
  const onSubmit = async (values: IssueLicenseRequest) => {
    const result = await issue(values).unwrap();
    // result.licenseFile = base64 encoded JWT-signed .lic
    downloadFile(`${values.customerName}.lic`, atob(result.licenseFile));
  };
  // ...
}
```

Backend:

```kotlin
// internal-admin-service: LicenseIssuer
fun issueLicense(req: IssueLicenseRequest): String {
    val claims = mapOf(
        "customer_id" to req.installationId,
        "tenants_max" to req.tenantsMax,
        "modules_enabled" to req.modulesEnabled,
        "valid_until" to req.validUntil.toString(),
        "features" to req.features,
        "issued_at" to Instant.now().toString(),
        "issued_by" to currentUser.email
    )
    return Jwts.builder()
        .setClaims(claims)
        .signWith(rsaPrivateKey, SignatureAlgorithm.RS256)
        .compact()
}
```

### 4.6. Support dashboard

```tsx
// apps/internal-admin/src/pages/support/InstallationHealthCard.tsx
import { useGetInstallationHealthQuery } from '@/features/installations/api';

export function InstallationHealthCard({ id, name }: Props) {
  const { data, isLoading } = useGetInstallationHealthQuery({ id }, { pollingInterval: 30_000 });

  if (isLoading) return <Skeleton />;
  if (!data) return null;

  const status =
    data.errorRate > 0.05 ? 'critical' :
    data.errorRate > 0.01 ? 'warning' : 'healthy';

  return (
    <div className={`card status-${status}`}>
      <h3>{name}</h3>
      <dl>
        <dt>Versiyon</dt><dd>{data.version}</dd>
        <dt>Pod</dt><dd>{data.podCount}</dd>
        <dt>DB Disk</dt><dd>{data.dbDiskGb} GB</dd>
        <dt>Error Rate (5m)</dt><dd>{(data.errorRate * 100).toFixed(2)}%</dd>
        <dt>Aktif kullanıcı</dt><dd>{data.activeUsers}</dd>
        <dt>Son Alert</dt><dd>{data.lastAlert?.message ?? '—'}</dd>
      </dl>
      <a href={`https://grafana.lumix.io/d/installation?var-id=${id}`} target="_blank">
        Grafana Dashboard
      </a>
    </div>
  );
}
```

### 4.7. Güvenlik kuralları (Internal panel)

- **2FA mecburi** (TOTP + WebAuthn)
- **Session lifetime 4 saat**, idle 30dk
- **All writes audit log'a** (super-audit Kafka topic; ayrı retention)
- **Müşteri verisine erişim** → "break-glass" mod, ticket + onay zinciri
- **Bulk operation** → tek seferde max N kayıt + dual confirmation
- **IP allowlist** → Lumix office + VPN exit IP'leri

## 5. Neden ayrı panel? (Alternatifler)

| Alternatif | Neden elendi |
|---|---|
| **Customer + Internal aynı app, role bazlı route** | Risk: bir XSS bug Lumix admin'in token'ını çalar → tüm installation'lara erişim |
| **Customer panelin uzantısı (`/internal` route)** | Aynı sebep + UX karmaşıklığı |
| **CLI tool** (Rails admin tipi) | Bazı işlemler için iyi ama UI gerekli (chart, dashboard) |
| **3rd party (Forest Admin, Retool)** | Bağımlılık + müşteri verisi 3. tarafa gider (KVKK risk) |
| **Ayrı app** ✅ | İzolasyon, güvenlik, deployment esnekliği |

### Trade-off

- **İki app maintain etmek**: shared core ile minimize ediyoruz (kullanılan slices, RTK Query setup paylaşımlı)
- **İki ayrı CI/CD pipeline**
- **Login flow farkı**: SSO için ek setup

## 6. Pratik örnek — bir destek talebi

```
1. Müşteri X arar: "Sistem yavaş çalışıyor, mesajlar gelmiyor."
2. Destek mühendisi internal-admin'e girer (SSO + 2FA)
3. /installations → X müşterisini ara
4. /installations/{id} açar:
   - Health: error rate %3 (warning), DB lag 800ms
   - Son alert: "Kafka consumer lag > 1000 messages (notification-service)"
5. "Grafana Dashboard" → metric'leri inceler
6. /ops → ArgoCD sync status: drift yok
7. Karar: "notification-service consumer scale yetmiyor, replica artır"
   → /ops'tan "Increase replica" butonu (HPA override request → ArgoCD PR + auto-merge)
8. 5dk sonra: error rate düşüyor
9. Müşteriye dön: "Çözdük, izlemeye devam edeceğiz"
10. Audit log'a: kim ne yaptı + ticket id otomatik
```

## 7. Tuzaklar

- **Internal admin endpoint'lerini customer Kong'unda expose etmek**: Sadece `internal-admin-service` ve sadece allowlisted ingress.
- **Müşteri DB'sine doğrudan SQL erişimi**: Asla! Adapter / service üzerinden, audit'li.
- **2FA bypass route**: Recovery flow'da bile 2FA atlanmamalı; dual-control + senior approval.
- **Session uzunluğu çok uzun**: Çalışan masasını terk edip masada panel açık → senior employee yetkisini başkası kullanır. Idle timeout sıkı.
- **Audit log'a internal yazmama**: "Lumix çalışanı yaptı, fark etmez" değil; SUPER-audit kritik.
- **Lisans üretme audit'siz**: Kim hangi müşteriye ne lisans verdi belli olmalı.
- **Yeni installation provisioning script'i partial fail**: Idempotent yap; orphan resource bırakmasın.
- **Cross-installation aggregate query müşteri verisini sızdırma**: Aggregate sonuçlar identifying-bilgi içermemeli (k-anonymity).
- **Internal admin'in test ortamı yoksa**: Production'da deneme yapan dev → felaket. Staging environment'ı zorunlu.
- **PII'ya erişim onay zinciri unutmak**: Break-glass mode olmadan müşteri verisi okunamamalı.
- **Network policy'siz panel**: K8s NetworkPolicy ile sadece allowlisted ingress class.
- **Dependency çakışması monorepo'da**: `@lumix/core` peerDependencies disiplinli yönet.

## 8. Diğer konularla ilişkisi

- [Customer Admin Panel](./customer-admin-panel) — müşteri tarafı
- [Rancher Cluster Management](./rancher-cluster-management) — multi-cluster K8s
- [Installation/Tenant/Scope](../tenancy-and-domain-model/installation-tenant-scope) — installation modeli
- [Lisanslama (tech stack §24)](../00-overview/02-technology-stack-decisions) — `.lic` formatı
- [Genel Mimari](../00-overview/03-overall-architecture) — Lumix sağlayıcı tarafı kutusu
- [Compliance & Privacy](../00-overview/02-technology-stack-decisions) — break-glass erişim
- [Shared Business Logic](../10-frontend-mobile/02-shared-business-logic) — `@lumix/core` paylaşımı

## 9. Daha derine

- Operator UX best practices: https://www.honeycomb.io/blog/observability-engineering-book
- Privileged Access Management: https://www.cyberark.com/what-is/privileged-access-management/
- Break-glass access: https://www.beyondtrust.com/blog/entry/break-glass-access
- Search keywords:
  - `internal admin panel saas multi tenant lifecycle`
  - `provisioning wizard terraform ansible orchestrate`
  - `cross customer audit log aggregation k anonymity`
  - `break glass access pattern saas operators`
  - `license generator jwt rs256 signed`

## 10. Sözlük

- **Internal Admin** — Lumix sağlayıcı ekibinin kullandığı yönetim paneli.
- **Installation** — Bir müşterinin tam Lumix kurulumu (kendi K8s, kendi DB).
- **Provisioning** — Yeni installation kurma süreci (Terraform + Ansible + ArgoCD).
- **`.lic` file** — JWT (RS256) imzalı lisans dosyası; offline doğrulanabilir.
- **Break-glass** — Acil durumlarda kullanılan kontrollü ayrıcalıklı erişim.
- **Super-audit** — Lumix çalışanlarının yaptığı işlemlerin ayrı audit stream'i.
- **HPA (Horizontal Pod Autoscaler)** — K8s pod replica auto-scale.
- **NOC** — Network Operations Center.
- **Health snapshot** — Bir installation'ın belirli andaki sağlık göstergeleri.
- **Tier** — Müşteri abonelik seviyesi (basic / standard / enterprise).
- **Phased rollout** — Yeni özelliği aşamalı kullanıma alma.
