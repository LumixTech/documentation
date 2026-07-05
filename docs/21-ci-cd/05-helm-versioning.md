---
title: Helm Chart Versioning ve Promotion
description: Chart version vs app version, semantic versioning, Helm repo (ChartMuseum veya GitLab built-in), promotion (dev → staging → prod), umbrella chart Lumix kullanımı.
sidebar_position: 5
---

## Bu sayfa ne anlatıyor?

Lumix 10+ microservice + altyapı bileşeni Helm chart'ı + tek umbrella chart yönetir. "Hangi versiyon hangi ortamda?" sorusuna **deterministik** cevap vermek için disiplinli **versioning** gerekir. Bu sayfa **chart version vs app version** ayrımını sıfırdan açıklar, **SemVer** uygulamasını, **promotion pipeline (dev → staging → prod)**, **umbrella chart pinning**, **OCI registry** (Helm 3.8+) kullanımı, ve **rollback compatibility** kurallarını gösterir. Hedef kitle: Helm temellerini bilen ([Helm Charts](../infra-devops/03-helm-charts.md)), CI/CD'yi tasarlayan ekip lideri.

## 1. Bu nedir? (Sıfırdan)

Helm chart'ı iki versiyon taşır:

### `version` (Chart Version)

Chart paketinin kendisinin versiyonu. **SemVer** zorunlu. Chart yapısı/template/values'ta değişiklik olunca artar.

### `appVersion` (App Version)

Chart'ın paketlediği uygulamanın versiyonu (örn. Docker image tag). String tip; semantik sınırlama yok ama biz SemVer kullanırız.

Örnek:
```yaml
# Chart.yaml
apiVersion: v2
name: academic-service
version: 0.4.1        # chart paket sürümü
appVersion: "1.4.2"   # academic-service uygulamasının sürümü
```

### Neden ikisi ayrı?

Bazı senaryolar:
- Chart template'i geliştirildi (yeni env var eklendi) ama uygulama versiyonu değişmedi → `version` bump, `appVersion` aynı.
- Uygulama yeni release oldu, chart template aynı → `appVersion` bump, `version` da bump (paket değişti çünkü `Chart.yaml` değişti).

### Günlük hayattan analoji

Bir kutu içinde ürün: kutunun versiyonu (chart) ile kutudaki ürünün versiyonu (app). Kutu tasarımı değişebilir (chart) ama içindeki ürün aynı sürüm olabilir; ya da ürün versiyon değişir, kutu güncellenir.

## 2. Hangi problemi çözüyor?

| Acı | Versioning disiplini yok | Disiplin var |
|---|---|---|
| "Production'da hangi versiyon?" | Belirsiz | `helm list -A` exact |
| Rollback hedefi | "Önceki neydi?" | `0.4.0` net |
| Aynı chart farklı release | Çakışma | `version` farklı |
| ArgoCD reconciliation | Drift mi yoksa update mi | `targetRevision` kontrol |
| Audit | Manuel takip | Git tag + chart registry |
| Multi-tenant promotion | Manuel kopyala | `helm upgrade --version` |
| Sub-chart dependency | Sürpriz upgrade | `Chart.lock` |

### Patlamış üretim hikayesi

Bir takım `latest` tag ile deploy yapıyordu. Yeni image push edildi, ArgoCD sync'i fark etmedi (chart version değişmemişti). Cluster'da eski + yeni image karışık çalıştı, schema migration yarısı yapıldı, requests fail. SemVer + immutable image + chart version bump bu acıyı dışlar.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. SemVer 2.0 hatırlatma

```
MAJOR.MINOR.PATCH[-PRERELEASE][+METADATA]

1.4.2
1.4.2-rc.1
1.4.2+20240527
```

- **MAJOR**: backwards-incompatible değişiklik (template breaking, schema breaking).
- **MINOR**: backwards-compatible yeni özellik.
- **PATCH**: bug fix, security patch.
- **PRERELEASE**: `-alpha`, `-beta`, `-rc.1` (pre-release).
- **METADATA**: build info.

### 3.2. Lumix versiyon politikası

| Değişiklik | Bump |
|---|---|
| Chart template'e yeni env eklendi (zorunlu) | MAJOR (chart) |
| Chart template'e yeni env eklendi (optional, default) | MINOR (chart) |
| Helper rename | MAJOR (chart) |
| Resource limit default değiştirildi | MINOR (chart) |
| Bug fix (yanlış yazılmış secret name) | PATCH (chart) |
| App tarafında yeni feature | MINOR (app) → MINOR (chart) da |
| App bug fix | PATCH (app) → PATCH (chart) da |

Yani: **app version değişirse chart version da bump edilir** (chart yeni image tag içerir, paket değişti).

### 3.3. CI'da otomatik versioning

Lumix kuralı: **Git tag = versiyon**. Tag formatı:
```
v<MAJOR>.<MINOR>.<PATCH>[-<prerelease>]
örn: v1.4.2, v1.4.3-rc.1
```

Pipeline:
```yaml
helm-package:
  script:
    - |
      if [[ -n "$CI_COMMIT_TAG" ]]; then
        CHART_VERSION="${CI_COMMIT_TAG#v}"
        APP_VERSION="${CI_COMMIT_TAG#v}"
      else
        CHART_VERSION="0.0.0-${CI_COMMIT_SHORT_SHA}"
        APP_VERSION="0.0.0-${CI_COMMIT_SHORT_SHA}"
      fi
    - helm package charts/${SERVICE} --version ${CHART_VERSION} --app-version ${APP_VERSION}
```

`main` branch push'unda `0.0.0-<sha>` (devel sürümler); tag push'unda gerçek SemVer.

### 3.4. Helm OCI registry (Helm 3.8+)

Helm chart'ı **OCI image gibi** registry'ye push:
```bash
helm registry login registry.lumix.io
helm push academic-service-0.4.1.tgz oci://registry.lumix.io/platform/charts
```

ArgoCD `repoURL: oci://registry.lumix.io/charts`, `chart: academic-service`, `targetRevision: 0.4.1`.

Bu, container image ile chart'ı **aynı registry**'de tutar; auth, RBAC, retention tek yerden.

### 3.5. `Chart.lock`

Sub-chart dependency'ler için lock file:
```yaml
# Chart.lock
dependencies:
  - name: postgresql
    repository: oci://registry.lumix.io/charts
    version: 15.5.0
digest: sha256:abcdef...
generated: "2026-05-27T14:30:00Z"
```

`helm dependency update` yenileme yapar. Lock dosyası Git'te.

### 3.6. Promotion akışı

```
1. Geliştirici PR açar → unit/integration test
2. Merge to main → 0.0.0-<sha> chart published → dev cluster auto-deploy
3. Smoke test on dev
4. Git tag v1.4.3-rc.1 → 1.4.3-rc.1 chart published → staging cluster deploy
5. UAT on staging
6. Git tag v1.4.3 → 1.4.3 chart published → manual approve → prod deploy
7. Production smoke test
8. Monitor; ihtiyaç olursa argocd app rollback
```

### 3.7. Umbrella chart pinning

Umbrella `lumix-platform/Chart.yaml`:

```yaml
dependencies:
  - name: identity-service
    version: "0.3.2"          # EXACT
    repository: oci://registry.lumix.io/charts
  - name: academic-service
    version: "0.4.1"
    repository: oci://registry.lumix.io/charts
  - name: finance-service
    version: "~0.2.5"         # >=0.2.5 <0.3.0
    repository: oci://registry.lumix.io/charts
  - name: kong
    version: "2.40.0"
    repository: https://charts.konghq.com
```

Lumix kuralı: **exact version pin** (`~` veya `^` değil). Sürprizi sıfıra indir. Update = explicit chart version bump.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Versiyon scheme

| Component | Versioning |
|---|---|
| Microservice chart (per-service) | SemVer (`0.4.1`) |
| Microservice app | SemVer (`1.4.2`) — chart `appVersion` |
| Umbrella `lumix-platform` chart | **CalVer** `YYYY.MM.PATCH` (örn. `2026.04.0`) — release ay bazlı |
| Umbrella `appVersion` | Umbrella'nın paketlediği "release suite" version (örn. `1.4.2`) |
| Customer profile | Independent — chart'a bağlı değil |

CalVer umbrella için seçildi çünkü:
- Release döngüsü ay bazlı (her ayın başında planlı release)
- Sürüm numarasına bakarak "ne zamanlı" hemen okunur
- Microservice'lerin SemVer'i ile karışmaz

### 4.2. Repository yapısı

```
gitlab.lumix.io/platform/microservices/<service>/
  └── charts/<service>/         # service-specific chart
       ├── Chart.yaml           # version (SemVer)
       └── ...

gitlab.lumix.io/platform/charts/lumix-platform/
  └── Chart.yaml                # version (CalVer)
  └── Chart.lock                # sub-chart pinning
```

Push:
```
oci://registry.lumix.io/platform/charts/identity-service:0.3.2
oci://registry.lumix.io/platform/charts/academic-service:0.4.1
oci://registry.lumix.io/platform/charts/lumix-platform:2026.04.0
```

### 4.3. Image tag stratejisi

Lumix kuralı: **image tag = app version (semver)**. `latest` yasak.

```yaml
# values.yaml (chart)
image:
  repository: registry.lumix.io/lumix/academic-service
  tag: ""   # boşsa Chart.appVersion kullanılır
```

CI tag push (v1.4.2) → image build → push `registry.lumix.io/lumix/academic-service:1.4.2` + `1.4.2-amd64-<sha>` (immutable).

### 4.4. Immutability

Lumix kuralları:
- **Aynı SemVer tekrar push edilmez** (registry write-once policy)
- Registry retention: tagged image 12 ay, untagged 7 gün

### 4.5. Pre-release versioning

```
v1.4.3-rc.1     # release candidate, staging deploy
v1.4.3-rc.2
v1.4.3          # GA, prod deploy candidate
```

ArgoCD `targetRevision: 1.4.3-rc.1` ile staging cluster sync.

### 4.6. Promotion sırası — Lumix ortamlar

```
┌─────────────────────────────────┐
│ DEV cluster                     │
│  ArgoCD ApplicationSet:         │
│    targetRevision: latest       │
│    (her main push otomatik)     │
└────────────────┬────────────────┘
                 │ (smoke test pass)
                 ▼
┌─────────────────────────────────┐
│ STAGING cluster (rc.* tag)      │
│  ArgoCD:                        │
│    targetRevision: 2026.04.0-rc.1│
└────────────────┬────────────────┘
                 │ (UAT pass)
                 ▼
┌─────────────────────────────────┐
│ PROD customer clusters          │
│  ArgoCD:                        │
│    targetRevision: 2026.04.0    │
│   (manual gate + per-customer)  │
└─────────────────────────────────┘
```

### 4.7. Customer-by-customer roll-out

Yeni umbrella version `2026.04.0` çıkınca:
- **Wave 1**: pilot müşteriler (önceden onay vermiş)
- **Wave 2**: tier-m müşteriler
- **Wave 3**: tier-l büyük müşteriler

Her wave 1-3 gün arayla; ArgoCD ApplicationSet'e label-based selector.

### 4.8. Rollback compatibility kuralları

Lumix kuralı: bir minor versiyon **N-1 ile geri uyumlu**. Yani `1.4.2 → 1.4.1` rollback güvenli (schema, config). `1.4.x → 1.3.x` rollback **resmî desteklenmez** (test edilmemiş).

Major versiyon (örn. `2026.04.0 → 2026.03.x`) rollback için **özel rollback runbook**.

### 4.9. Helm secret hook çakışması

`pre-upgrade` Flyway migration hook olduğu durumda: rollback Flyway için **forward-only** kabul; rollback DB schema'sını otomatik geri almaz. Schema rollback için **expand/contract pattern** ([Database Architecture](../database-architecture)).

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **`latest` tag** | Drift, reproducibility yok. Yasak. |
| **Branch-based version** (`feature-xyz`) | Production'a uygunsuz; immutable değil. |
| **Hash-based version only** | İnsan okuyamaz; release notes zor. |
| **Git commit SHA = chart version** | Çok düşük readability; SemVer artık yok. |
| **Microservice chart umbrella'sız** | Manuel orchestration; sürüm uyumu kayıp. |
| **Kustomize overlays + sürüm yok** | Helm'in versioning'i kaybedilir. |

### Kabul ettiğimiz trade-off'lar

- **Tag spam**: her release ayrı tag. Git annotated tags ile organize.
- **CalVer umbrella + SemVer micro**: iki şema karışıklığı; doc'la net.
- **Immutable image — registry storage**: aylık 100+ image. Retention policy.
- **Pre-release tag uzun**: `v1.4.3-rc.1`; release notes zorunlu.

### Tekrar değerlendirme tetikleyicileri

- Müşteri başına farklı version (custom) gerekirse → branch-per-customer (zor); şu an aynı umbrella version.
- Çok hızlı release döngüsü (haftalık) gerekirse → CalVer yerine farklı format.

## 6. Pratik örnek

### 6.1. Chart bump örneği

```yaml
# Önce
apiVersion: v2
name: academic-service
version: 0.4.0
appVersion: "1.4.1"

# Sonra (app bug fix)
version: 0.4.1
appVersion: "1.4.2"
```

PR + CI tag `v1.4.2` push → chart `academic-service-0.4.1.tgz` ve image `academic-service:1.4.2` üretilir.

### 6.2. Umbrella chart bump

```yaml
# Önce: 2026.03.5
dependencies:
  - name: academic-service
    version: "0.4.0"
  - name: finance-service
    version: "0.2.4"

# Sonra: 2026.04.0
version: 2026.04.0
appVersion: "2026.04.0"
dependencies:
  - name: academic-service
    version: "0.4.1"     # bump
  - name: finance-service
    version: "0.2.5"     # bump
```

`helm dependency update` ile `Chart.lock` güncellenir; commit.

### 6.3. ArgoCD `targetRevision` güncelleme

```yaml
# Tier m müşterileri için umbrella 2026.04.0
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: lumix-platform-omer-okullari
spec:
  source:
    repoURL: oci://registry.lumix.io/charts
    chart: lumix-platform
    targetRevision: 2026.04.0   # eski: 2026.03.5
```

Git PR + merge → ArgoCD sync → cluster'da `helm upgrade lumix-platform --version 2026.04.0`.

### 6.4. Rollback

```bash
# ArgoCD rollback
argocd app history lumix-platform-omer-okullari
# 14  2026.04.0
# 13  2026.03.5

argocd app rollback lumix-platform-omer-okullari 13
```

Veya Git revert:
```bash
git revert <commit-bump-to-2026.04.0>
git push
# ArgoCD auto-sync → 2026.03.5'e dön
```

### 6.5. Lokal chart paketleme test

```bash
cd charts/academic-service

# Lint
helm lint .

# Dependency update
helm dependency update

# Package
helm package . --version 0.4.1 --app-version 1.4.2

# Push (dry-run)
helm push academic-service-0.4.1.tgz oci://registry.lumix.io/platform/charts --dry-run

# Pull verify
helm pull oci://registry.lumix.io/platform/charts/academic-service --version 0.4.1
```

### 6.6. CI tag-based release

```bash
# Local: prepare release
git checkout main
git pull
# Chart.yaml: version: 0.4.0 → 0.4.1, appVersion: 1.4.1 → 1.4.2
git commit -am "chore(academic): bump to 1.4.2"
git push

# Tag (annotated)
git tag -a v1.4.2 -m "academic-service 1.4.2 — fix XYZ"
git push origin v1.4.2

# Pipeline tetiklenir:
# - build-image academic-service:1.4.2
# - trivy scan
# - helm package academic-service-0.4.1
# - publish-chart
# - dev deploy (auto)
# - staging deploy (rc tag varsa)
# - prod deploy (manual)
```

### 6.7. Customer values pin etmek

```yaml
# customers/omer-okullari/values.yaml
academic-service:
  image:
    tag: "1.4.2"        # umbrella appVersion ile aynı, ama explicit
  replicaCount: 4
```

Bazı müşteriler "stay on 1.4.1" diyebilir; bu durumda overlay'le pin. Ama Lumix kuralı: stay-on-old olabildiğince kısa süreli.

## 7. Dikkat edilecek tuzaklar

- **`latest` tag kullanmak**: immutable değil; deterministik deploy yok; debug imkansız. Lumix kuralı: yasak.
- **Chart version'ı bump etmeden template değiştirmek**: ArgoCD cache'leyebilir, yeni manifest yayılmaz. Her template change = chart version bump.
- **AppVersion'ı string olarak `1.4.2`-without-quotes yazmak**: YAML 1.4.2'yi number olarak parse edebilir. Hep `"1.4.2"`.
- **`Chart.lock` Git'e koymamak**: dependency drift; aynı `helm dependency update` farklı sonuç. Lock dosyası Git'te zorunlu.
- **Sub-chart range `^0.4.0`**: minor bump otomatik gelir → sürpriz. Exact pin.
- **Tag delete + re-push**: registry policy izin verirse drift. Lumix kuralı: immutable; registry write-once.
- **Pre-release tag'ini production'a deploy**: rc.* prod yasak.
- **Versiyon atlamalı release**: `1.4.1 → 1.6.0` → 1.5.x'in test'i yapılmadı; ne kadar geri uyumlu belirsiz. Sequential.
- **Helm 3.7 ile OCI registry kullanmak**: 3.8+ stable. Helm version pin.
- **Rollback hedefi N-2 versiyonu**: schema/config breaking olabilir. Lumix kuralı: rollback sadece N-1.
- **Chart version artırırken Git tag atmamak**: registry'de chart var ama Git'te kanıt yok. Tag zorunlu.
- **Customer values'ta image tag override + chart appVersion farklı**: hangisi geçerli karışıklık. Lumix kuralı: customer values minimal, chart default'a güven.

## 8. Diğer konularla ilişkisi

- [Helm Charts](../infra-devops/03-helm-charts.md) — chart yapısı
- [GitLab CI Pipelines](./02-gitlab-ci-pipelines.md) — versioning pipeline
- [ArgoCD GitOps](./04-argocd-gitops.md) — `targetRevision` ile chart version pin
- [GitLab CE Self-Hosted](./01-gitlab-ce-self-hosted.md) — OCI registry
- [Trivy Image Scanning](./03-trivy-image-scanning.md) — version'ı bilinen image scan
- [Database Architecture](../database-architecture) — schema migration version uyumu
- [Customer Onboarding Pipeline](../20-iac-provisioning/03-customer-onboarding-pipeline.md) — umbrella chart version deploy

## 9. Daha derine inmek için

- SemVer 2.0: [https://semver.org/](https://semver.org/)
- Helm doc — Chart Lifecycle Management
- "Versioning APIs" pattern (Martin Fowler)
- "Continuous Delivery" — promotion pipeline kavramları
- Search keyword'leri: *"helm chart version vs appversion"*, *"helm oci registry push"*, *"helm dependency lock chart.lock"*, *"calver vs semver"*, *"helm rollback strategy"*

## 10. Sözlük

- **`version` (Chart Version)**: Chart paketinin SemVer'i.
- **`appVersion`**: Chart'ın paketlediği uygulamanın versiyonu (string).
- **SemVer (Semantic Versioning)**: MAJOR.MINOR.PATCH formatı.
- **CalVer (Calendar Versioning)**: YYYY.MM.PATCH gibi tarih bazlı format.
- **Pre-release**: `-rc.1`, `-beta`, `-alpha` ekli versiyonlar.
- **Immutable tag**: Bir kez push edilen, üzerine yazılmayan tag.
- **OCI registry**: Container image standardı (Helm 3.8+ chart push).
- **`Chart.lock`**: Dependency versiyonlarını sabitleyen lock dosyası.
- **Umbrella chart**: Alt-chart'ları bir araya getiren üst-chart.
- **`targetRevision` (ArgoCD)**: Hangi chart sürümünü deploy edeceği.
- **Promotion**: Bir version'ı dev → staging → prod arasında ilerletme süreci.
- **Wave roll-out**: Müşteri/cluster gruplarına aşamalı yayılım.
- **Rollback compatibility**: Hangi version arası geri dönüş güvenli.
- **`helm rollback`**: Helm release'i önceki revision'a döndürme.
- **Git annotated tag**: `-a` ile mesajlı tag; release için zorunlu.
