---
title: Trivy — Container Image ve Config Scanning
description: Trivy nedir, container image CVE scan, GitLab CI gate (high/critical CVE → fail), SBOM oluşturma, vulnerability database.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Lumix imajları production'a gitmeden önce **bilinen güvenlik açıklarına** karşı taranır. **Trivy** (Aqua Security tarafından geliştirilen, açık kaynak), Lumix'in bu görev için standart aracıdır. Bu sayfa Trivy'i sıfırdan anlatır, **vulnerability database** akışını gösterir, **container image scan** + **filesystem scan** + **config scan** + **secret scan** + **SBOM** çıktılarını açıklar, Lumix'in GitLab CI pipeline'ında nasıl **gate** olarak konumlandığını detaylandırır ve **false-positive yönetimi** prosedürünü tarif eder. Hedef kitle: CI/CD ve Helm temellerini bilen ekip; security mühendisliğine yeni dokunan geliştirici.

## 1. Bu nedir? (Sıfırdan)

**Trivy**, tek binary olarak çalışan kapsamlı bir güvenlik tarayıcısı. Tarayabilir:
- **Container image** (Docker, OCI): işletim sistemi paketleri + dil-spesifik bağımlılıklar.
- **Filesystem**: lokal dizinde aynı analiz.
- **Git repository**: uzaktan kod tabanı.
- **Kubernetes cluster**: deployed kaynaklar.
- **IaC config**: Terraform, K8s manifest, Helm chart misconfiguration.
- **Secret detection**: kod içinde sızdırılmış token/parola.
- **SBOM** (Software Bill of Materials): CycloneDX, SPDX format.

Tarama sonucu **CVE** (Common Vulnerabilities and Exposures) ID'leri ve severity (LOW/MEDIUM/HIGH/CRITICAL) raporlar.

### Günlük hayattan analoji

Bir paket (image) içinden çıkanları sayan ve "bu üründe XYZ-2024 numaralı resmi geri çağırma var" diyebilen müfettiş. İşleyiş: paketi açar (image layer extraction), içindeki üreticilerin (OS package manager, language ecosystem) inventory'sini çıkarır, public CVE veritabanı ile karşılaştırır, rapor üretir.

## 2. Hangi problemi çözüyor?

Container image'ı build edip prod'a gönderirken:
- Base image'da eski OpenSSL → CVE-2024-XXXX
- Spring Boot 3.6.x'te transitive dep'te Log4Shell-benzeri açık
- Hardcoded API key dosyada
- Dockerfile RUN olarak `wget | sh` gibi anti-pattern

| Acı | Trivy yok | Trivy var |
|---|---|---|
| CVE keşfi | Manuel `apt audit` + bağımlılık taraması | `trivy image` tek komut |
| Yeni CVE çıkışı | "Acaba bizde var mı?" | Pipeline gate + alert |
| SBOM | Manuel envanter | `trivy image --format cyclonedx` |
| IaC misconfiguration (privileged: true vb.) | Manuel review | `trivy config` |
| Hardcoded secret | Manuel grep | `trivy fs --security-checks secret` |
| Compliance (KVKK/PCI) | "Konuşalım" | Tarama raporu kanıt |

### Patlamış üretim hikayesi

Log4Shell ve Spring4Shell sürpriz değildi: yıllardır bilinen Java ekosistemi açıkları. Bir takım build sırasında scan yapmıyordu. Spring4Shell çıktığında: kim hangi servisi yamayacak? Manuel envanter 3 gün sürdü. Pipeline'da Trivy gate olsaydı: tüm etkilenen image'lar otomatik listelenir.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Trivy nasıl çalışır?

```
1. Image pull (veya layer cache)
2. Layer extraction (OS + language packages)
3. Inventory:
     - OS: /var/lib/dpkg/status, /lib/apk/db/installed, rpm db
     - Java: jar /pom.xml, layered jar lib/
     - Node: package-lock.json, yarn.lock
     - Python: requirements.txt, Pipfile.lock, poetry.lock
     - Go: binary metadata, go.sum
     - Ruby, Rust, ...
4. Vulnerability DB lookup (Trivy DB — OCI artifact, günlük güncellenir)
5. Severity'e göre filter
6. Output (table, json, sarif, cyclonedx, junit, html)
```

### 3.2. Vulnerability DB

- **Trivy DB** (Aqua) → OSV.dev + NVD + GitHub Advisory + Red Hat + Alpine + Debian + …
- **Format**: BoltDB
- **Distribution**: OCI artifact (`ghcr.io/aquasecurity/trivy-db`)
- **Güncelleme**: Trivy çalıştığında auto-update (cache 24h).

Lumix kararı: **DB'yi Lumix-internal registry'ye mirror et** (offline ve hızlı CI cache).

### 3.3. Komutlar

```bash
# Image scan
trivy image registry.lumix.io/lumix/academic-service:1.4.2

# Severity filter
trivy image --severity HIGH,CRITICAL --exit-code 1 ...

# Format
trivy image --format json -o report.json ...
trivy image --format sarif -o report.sarif ...
trivy image --format cyclonedx -o sbom.json ...
trivy image --format template --template "@contrib/junit.tpl" -o junit.xml ...

# Config scan (Dockerfile, K8s manifest, Terraform)
trivy config .

# Secret scan
trivy fs --security-checks secret .

# Filesystem scan
trivy fs --security-checks vuln,secret,config .

# Repository scan (uzaktan)
trivy repo https://gitlab.lumix.io/platform/microservices/academic-service.git
```

### 3.4. Severity tanımları

| Severity | Açıklama |
|---|---|
| `CRITICAL` | Yaygın olarak istismar edilen, RCE/uzaktan auth bypass; **derhal yama**. |
| `HIGH` | Önemli açık; planlı yama, prod gate. |
| `MEDIUM` | Bilgi sızıntısı, DoS; backlog'a alınır. |
| `LOW` | Düşük etki; review opsiyonel. |
| `UNKNOWN` | Eski/eksik info. |

Lumix CI gate:
- **CRITICAL, HIGH** → fail (image production'a gitmez).
- **MEDIUM** → uyarı, devam.
- **LOW, UNKNOWN** → loglanır, ignore.

### 3.5. SBOM

CycloneDX format JSON çıkışı her bağımlılığı listeler. Lumix kuralı: her release image'ı için SBOM artifact olarak saklanır + Dependency-Track gibi araçla incelenir (gelecek).

### 3.6. Misconfiguration scan

Trivy K8s manifest, Helm chart, Dockerfile, Terraform misconfig'leri için **rule set** tutar (`AVD-...` ID'leri):
- `privileged: true`
- `runAsRoot`
- Eksik resource limit
- Hardcoded port
- AWS S3 public bucket
- vs.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Pipeline gate yeri

```
[ build-image ]
       │
       ▼
[ trivy-image-scan ]    ← bu adım fail = pipeline durur
[ trivy-config-scan ]   ← Dockerfile + K8s manifest
[ trivy-secret-scan ]   ← code base secret
       │
       ▼
[ helm-package ]
       │
       ▼
[ publish-chart ]
```

CI gate severity: **CRITICAL, HIGH → fail**.

### 4.2. Trivy version ve image

```yaml
trivy-image-scan:
  stage: scan
  image: aquasec/trivy:0.50.0       # version-pinned
  variables:
    TRIVY_NO_PROGRESS: "true"
    TRIVY_CACHE_DIR: ".trivycache"
  cache:
    key: trivy-${CI_COMMIT_REF_SLUG}
    paths: [.trivycache]
  script:
    - trivy image
        --severity CRITICAL,HIGH
        --exit-code 1
        --no-progress
        --format template
        --template "@/contrib/junit.tpl"
        -o trivy-report.xml
        "${CI_REGISTRY_IMAGE}:${CI_COMMIT_SHA}"
  artifacts:
    when: always
    reports:
      junit: trivy-report.xml
    paths: [trivy-report.xml]
    expire_in: 1 month
```

### 4.3. SBOM otomatik üretim

```yaml
trivy-sbom:
  stage: scan
  image: aquasec/trivy:0.50.0
  needs: [build-image]
  script:
    - trivy image --format cyclonedx -o sbom.cdx.json "${CI_REGISTRY_IMAGE}:${CI_COMMIT_SHA}"
    - trivy image --format spdx-json -o sbom.spdx.json "${CI_REGISTRY_IMAGE}:${CI_COMMIT_SHA}"
  artifacts:
    paths:
      - sbom.cdx.json
      - sbom.spdx.json
    expire_in: 12 month
```

### 4.4. False-positive yönetimi

`.trivyignore` dosyası:
```
# Reason: only present in non-production tooling; Spring Boot config not exposed.
# Tracked: SECURITY-203, review by 2026-09-01
CVE-2024-12345
CVE-2024-67890
```

Veya CLI argümanı:
```bash
trivy image --ignorefile .trivyignore ...
```

Lumix kuralı:
- Her ignore **gerekçeli + sahipli + son inceleme tarihli**.
- Aylık review: ignore hâlâ geçerli mi?
- Ignore eklemek = `security-team` review zorunlu (CODEOWNERS).

### 4.5. Trivy DB cache stratejisi

CI runner'larda `.trivycache` dizini gradle-style cache. Her job'a download'lamak yerine cache'den okur. Aylık fresh download.

```yaml
trivy-image-scan:
  cache:
    key: trivy-db-${CI_PIPELINE_IID_DIV_30}    # ~30 pipeline'da bir refresh
    paths: [.trivycache]
```

### 4.6. Misconfig scan — Dockerfile ve K8s

```yaml
trivy-config-scan:
  stage: scan
  image: aquasec/trivy:0.50.0
  script:
    - trivy config
        --severity CRITICAL,HIGH
        --exit-code 1
        --format template
        --template "@/contrib/junit.tpl"
        -o trivy-config-report.xml
        .
  artifacts:
    when: always
    reports:
      junit: trivy-config-report.xml
```

Tarama hedefi: Dockerfile + `charts/`+ `manifests/`.

### 4.7. Secret scan

```yaml
trivy-secret-scan:
  stage: scan
  image: aquasec/trivy:0.50.0
  script:
    - trivy fs --security-checks secret --severity HIGH,CRITICAL --exit-code 1 .
```

Bulunan: AWS key, GitHub PAT, JWT, RSA private key vb.

### 4.8. Multi-arch image scan

Lumix bazı image'ları multi-arch (amd64 + arm64) build eder; her arch ayrı scan'dan geçer.

```yaml
trivy-image-scan-amd64:
  parallel:
    matrix:
      - PLATFORM: linux/amd64
      - PLATFORM: linux/arm64
  script:
    - trivy image --platform $PLATFORM ${CI_REGISTRY_IMAGE}:${CI_COMMIT_SHA}
```

### 4.9. Dashboard ve alert

- GitLab UI: JUnit report → Test panel "Trivy" suite.
- Loki dashboard: Trivy JSON output Promtail ile → Grafana panel "Critical CVE count by service".
- Slack alert: pipeline failure + severity table.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Anchore Engine / Grype** | Grype paralel araç; iyi ama Trivy daha hızlı + tek binary + multi-domain (vuln+config+secret+SBOM). |
| **Clair** (Quay) | Quay-bound, geliştirme yavaş. |
| **Snyk** | Ticari, free tier sınırlı, KVKK self-host endişe. |
| **JFrog Xray** | Artifactory tied, ticari. |
| **Docker Scout** | Docker Hub'a bağlı; Trivy daha açık. |
| **kube-bench** | Sadece CIS Kubernetes; Trivy daha geniş. |

### Kabul ettiğimiz trade-off'lar

- **False positive oranı**: tüm tarayıcılarda var; ignore + review disiplin gerektirir.
- **DB güncellik**: Trivy DB günlük güncellenir; saatlik değil. 0-day için ek monitoring kanalı.
- **Performance**: küçük image'da hızlı, büyük image'da (>1 GB) 30-60s.

### Tekrar değerlendirme tetikleyicileri

- Trivy DB'nin Aqua tarafında değişimi (lisans, paywall) → Grype + OSV.
- Dependency-Track entegrasyonu zorunlu olunca → CycloneDX flow.

## 6. Pratik örnek

### 6.1. Lokal manuel scan

```bash
# Image'ı build et
docker build -t academic-service:local .

# Tara
trivy image --severity CRITICAL,HIGH academic-service:local

# Çıktı örnek
# academic-service:local (debian 12)
# ============================
# Total: 3 (HIGH: 2, CRITICAL: 1)
#
# ┌──────────────┬────────────────┬──────────┬────────────────┬─────────────────┐
# │   Library    │ Vulnerability  │ Severity │ Installed Ver. │  Fixed Version  │
# ├──────────────┼────────────────┼──────────┼────────────────┼─────────────────┤
# │ libssl3      │ CVE-2024-2511  │ HIGH     │ 3.0.11-1       │ 3.0.13-1~deb12u1│
# └──────────────┴────────────────┴──────────┴────────────────┴─────────────────┘
```

### 6.2. JUnit format CI raporu

```bash
trivy image \
  --format template --template "@contrib/junit.tpl" \
  -o trivy-report.xml \
  --severity CRITICAL,HIGH \
  --exit-code 1 \
  registry.lumix.io/lumix/academic-service:1.4.2
```

GitLab Test panel'inde her CVE bir "failed test" görünür. Görsel.

### 6.3. SBOM CycloneDX örnek (parça)

```json
{
  "bomFormat": "CycloneDX",
  "specVersion": "1.5",
  "version": 1,
  "metadata": {
    "timestamp": "2026-05-27T14:30:00Z",
    "tools": { "components": [ { "name": "trivy", "version": "0.50.0" } ] },
    "component": {
      "type": "container",
      "name": "registry.lumix.io/lumix/academic-service:1.4.2"
    }
  },
  "components": [
    {
      "type": "library",
      "bom-ref": "pkg:maven/org.springframework.boot/spring-boot-starter-web@3.3.1",
      "name": "spring-boot-starter-web",
      "version": "3.3.1",
      "purl": "pkg:maven/org.springframework.boot/spring-boot-starter-web@3.3.1"
    }
  ]
}
```

### 6.4. Misconfig örnek (Dockerfile)

```
# .Dockerfile
FROM eclipse-temurin:21-jdk
USER root                              # ← AVD-DS-0002 (HIGH)
COPY app.jar /app/
ADD https://malicious.example.com/key.tar /tmp/   # ← AVD-DS-0009
ENTRYPOINT java -jar /app/app.jar
```

Trivy config output:
```
Dockerfile (dockerfile)
=======================
Total: 2 (HIGH: 2)

HIGH: Last USER command in Dockerfile should not be 'root'
═════════════════════════════════════════════════════════
ID: DS002
Severity: HIGH

HIGH: Add file from a URL
═════════════════════════════════════
ID: DS009
```

### 6.5. K8s manifest scan

```bash
trivy config charts/academic-service/
```

Çıktı (örnek):
```
ChartName/templates/deployment.yaml (kubernetes)
===============================================
Total: 1 (HIGH: 1)

HIGH: Container 'app' of Deployment should set 'securityContext.allowPrivilegeEscalation' to false
ID: AVD-KSV-0001
```

### 6.6. CRITICAL bulgu — acil müdahale akışı

```
1. Pipeline fail → Slack alert (#security)
2. Hangi servisler etkileniyor? Trivy output + ignore list incele
3. Fix planı:
   a. Bağımlılık güncellemesi (build.gradle.kts version bump)
   b. Base image bump (eclipse-temurin:21-jdk-X.X.X)
   c. Şiddetli durumda: yama bekleyene kadar ignore + WAF rule (ModSecurity)
4. PR + merge + pipeline retry + production deploy
5. Audit log: incident-tracker'a kaydet
```

### 6.7. Helm test entegrasyonu

```yaml
helm-test:
  stage: test
  image: alpine/helm:3.15.0
  needs: [helm-package]
  script:
    - apk add --no-cache curl
    - curl -sSL https://github.com/aquasecurity/trivy/releases/download/v0.50.0/trivy_0.50.0_Linux-64bit.tar.gz | tar xz -C /tmp
    - /tmp/trivy config --severity CRITICAL,HIGH --exit-code 1 charts/academic-service/
```

## 7. Dikkat edilecek tuzaklar

- **`--exit-code 0` koyup pipeline geçirmek**: scan çalışır, ama gate yok. CI eşdeğeri "yapmıyorum". `--exit-code 1` zorunlu.
- **`.trivyignore` gözden kayıyor**: ignore eklendi, kimse takip etmiyor. Lumix kuralı: ignore = expiry tarihli + gerekçeli + sahip.
- **Trivy DB cache iyi yapılandırılmamış**: her job 50 MB indirir, yavaş + bant genişliği. Cache key strateji + Lumix internal mirror.
- **Sadece image scan**: config scan, secret scan eksik = ilk gün hatalar. Trio çalıştır.
- **Severity sınıflandırması yanlış kullanılmış**: takım LOW'a da gate koyup pipeline patlar → "ignore frenzy". HIGH/CRITICAL fail; MEDIUM uyarı; LOW info.
- **Distroless base image'ı OS package olmadığı için "all clean"**: Java jar bağımlılıkları hâlâ taranır; ama OS layer rapor "0 vuln" göstermez korkutmasın.
- **Multi-stage build'in son stage'i taranmalı**: ilk stage (builder) prod'a gitmiyor; gereksiz CVE listesi. Trivy doğru tag'i taramalı.
- **`-q` ile output'u susturmak**: hata mesajları gizlenir. JSON output + parse tercih.
- **Secret scan'i kod base'inde "false positive" kabul etmek**: test/fixture içindeki sahte token'lar trivy'i tetikler. `.trivyignore` veya `--skip-files`.
- **SBOM saklamamak**: gelecekte CVE çıktığında "kim etkilendi" cevabı için SBOM şart. Min 12 ay.
- **Trivy version pin yok**: yeni versiyon false-positive davranışı değiştirir → ani pipeline fail. Version pin + test cluster'da deneme.
- **Network restrict cluster'da DB indirme fail**: Lumix internal mirror'a yönlendir (`TRIVY_DB_REPOSITORY=registry.lumix.io/trivy-db`).

## 8. Diğer konularla ilişkisi

- [GitLab CI Pipelines](./gitlab-ci-pipelines) — scan job entegrasyonu
- [ModSecurity WAF](../infra-devops/modsecurity-waf) — runtime saldırı; Trivy build-time
- [Helm Charts](../infra-devops/helm-charts) — config scan hedefi
- [Helm Versioning](./helm-versioning) — yamalı image yeni release
- [Audit Log](../security-compliance) — SBOM audit kanıt
- [Compliance](../security-compliance) — KVKK security baseline

## 9. Daha derine inmek için

- Resmi doc: [https://aquasecurity.github.io/trivy/](https://aquasecurity.github.io/trivy/)
- Trivy GitHub: [https://github.com/aquasecurity/trivy](https://github.com/aquasecurity/trivy)
- CycloneDX: [https://cyclonedx.org/](https://cyclonedx.org/)
- "Container Security" — Liz Rice
- OWASP SCVS (Software Component Verification Standard)
- Search keyword'leri: *"trivy db cache mirror"*, *"trivy junit report gitlab"*, *"trivy ignorefile best practices"*, *"sbom cyclonedx vs spdx"*

## 10. Sözlük

- **Trivy**: Açık kaynak çok-domain güvenlik tarayıcısı (Aqua Security).
- **CVE (Common Vulnerabilities and Exposures)**: Bilinen güvenlik açığı ID.
- **CVSS (Common Vulnerability Scoring System)**: 0-10 arası severity skoru.
- **SBOM (Software Bill of Materials)**: Yazılım bileşen envanteri.
- **CycloneDX / SPDX**: SBOM standart formatları.
- **Trivy DB**: BoltDB tabanlı vulnerability veritabanı (OCI artifact).
- **Severity (LOW/MEDIUM/HIGH/CRITICAL)**: Açığın etki ciddiyeti.
- **`.trivyignore`**: Belirli CVE/AVD ID'lerini ignore etme dosyası.
- **Misconfiguration**: Yanlış kurulum (privileged container, public S3 vs.).
- **Secret scan**: Kod base'inde sızdırılmış token/parola arama.
- **Distroless image**: OS package manager olmayan minimal base (Google).
- **Multi-stage build**: Dockerfile'da build + runtime aşamalarını ayırma.
- **AVD (Aqua Vulnerability Database)**: Trivy'nin misconfig kural ID prefix'i.
- **Dependency-Track**: SBOM-driven sürekli güvenlik izleme platformu.
