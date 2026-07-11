---
title: Merkezi Config Repo (GitOps)
description: Tüm servislerin ortam/müşteri konfigürasyonunu tek bir Git repo'dan (lumix-config) katmanlı values ile yönetmek; ArgoCD render eder, ConfigMap üretir, Reloader restart eder, sırlar Vault'ta kalır.
sidebar_position: 6
---

# Merkezi Config Repo — Tek Kaynaktan Konfigürasyon

## Bu sayfa ne anlatıyor?

Şu ana kadar her servisin `application.yml`'i **image içinde bir şablon** olarak
duruyordu ([Konfigürasyon & Çalıştırma](../24-codebase-guide/04-konfigurasyon-ve-calistirma.md));
gerçek değerler ise Helm values → ConfigMap → env yoluyla dışarıdan basılıyordu.
Bu değerler bugün dağınık (chart default'u, tier overlay'i, müşteri overlay'i, ArgoCD
override'ı ayrı ayrı yerlerde). Bu sayfa, **tüm ortam/müşteri konfigürasyonunun tek bir
Git deposunda** (`lumix-config`) toplandığı, **GitOps ile** dağıtıldığı, sektör-standardı
**config-as-code** modelini sıfırdan anlatır: neden tek repo, katmanlı values hiyerarşisi,
değerin `git push`'tan pod'a kadar akışı, sır/sırsız ayrımı ve neden **Spring Cloud
Config gibi runtime config sunucusu değil** GitOps seçildiği. Hedef kitle: Helm ve ArgoCD
temellerini bilen ([Helm Charts](../infra-devops/03-helm-charts.md),
[ArgoCD GitOps](./04-argocd-gitops.md)) geliştirici/DevOps.

## 1. Bu nedir? (Sıfırdan)

"Konfigürasyonu tek yerden yönetmek" cümlesi iki farklı şey demek olabilir; ikisi de
sektörde standarttır ama farklı problemleri çözer:

| "Tek yer" ne demek? | Standart çözüm | Ne zaman |
|---|---|---|
| **Tek kaynak / tek repo** (source of truth) — tüm config tek Git deposunda, oradan dağıtılır | **GitOps config repo** (config-as-code) | K8s-native dünyada varsayılan |
| **Runtime tek merkez** — servisler açılışta/çalışırken merkezi bir sunucudan config çeker | **Spring Cloud Config Server** / **Consul KV** | Redeploy'suz canlı config değişimi gerekiyorsa |

**Lumix kararı: birinci yol** — ayrı bir `lumix-config` Git deposu tek gerçek kaynaktır.
Uygulama koduna gömülü hiçbir ortam değeri yoktur ([felsefe](../24-codebase-guide/04-konfigurasyon-ve-calistirma.md#1-konfigürasyon-felsefesi-koda-değil-ortama-bağla));
her ortam/müşteri farkı bu repo'daki **katmanlı values dosyalarından** gelir. ArgoCD bu
repo'yu izler, Helm ile render eder, çıkan ConfigMap'i cluster'a uygular.

### Günlük hayattan analoji

Bir zincir mağazanın tüm şubelerinin kuralları **tek bir kural kitabında** yazılıdır.
Genel kurallar herkes için; sonra "büyük şube" eki, sonra "İstanbul şubesi" eki gelir —
her ek bir öncekini gerektiği kadar ezer. Şubede kimse kuralı tek başına değiştiremez;
değişiklik kitaba (Git'e) yazılır, oradan tüm şubelere dağılır. `lumix-config` bu kural
kitabıdır; ArgoCD dağıtım şirketidir.

### Config'in üç fiziksel yeri — karıştırma

| Katman | Nerede yaşar | Ne tutar | Değişince |
|---|---|---|---|
| **Şablon** | `application.yml` (servis image'ında) | Hangi ayar var + `${VAR:default}` | Yeni image build |
| **Değer (sırsız)** | **`lumix-config` repo → ConfigMap** | `DB_HOST`, topic adı, pool size, feature flag | `git push` |
| **Değer (sır)** | **Vault → ExternalSecret** | Parola, token, API key | Vault'ta rotate |

Bu sayfa ortadaki satırı — **sırsız değerlerin tek merkezi** — anlatır. Sırlar asla bu
repo'ya girmez ([External Secrets / Vault](../security-compliance/)).

## 2. Hangi problemi çözüyor?

Config değerleri bugün dört ayrı yere yayılmış durumda: chart `values.yaml` default'u,
`values-base.yaml`, `values-tier-*.yaml`, müşteri overlay'i ve ArgoCD override'ı. Merkezi
bir repo olmadan yaşanan acılar:

| Acı | Merkezi repo yok | Merkezi repo (GitOps) var |
|---|---|---|
| "Ömer Okulları'nda `DB_POOL_SIZE` kaç?" | Chart mı, overlay mı, elle mi ezilmiş — belirsiz | `lumix-config/installations/omer-okullari/values.yaml` tek yer |
| Aynı ayarı 11 servise vermek | 11 dosyada tekrar | `base/` katmanında bir kez |
| "Kim ne zaman değiştirdi?" | İz yok | `git log` + PR review |
| Yanlış değer prod'a gitti | Elle geri al, hangi hâlde bilmiyorsun | `git revert` → ArgoCD eski hâli sync eder |
| Yeni müşteri onboarding | Config'i baştan derle | Yeni klasör + tier seç, katmanlar hazır |
| ConfigMap değişti ama pod eski değeri okuyor | Elle `rollout restart` | **Reloader** otomatik restart |

### Patlamış üretim hikayesi

Bir ekip config'i chart values + elle `kubectl edit configmap` karışımıyla yönetiyordu.
Bir mühendis acil bir Kafka broker adresini `kubectl edit` ile düzeltti; Git'te iz yok.
Üç hafta sonra chart upgrade'i o ConfigMap'i eski (yanlış) hâline geri yazdı, tüm
event akışı durdu. Kimse "doğru değer neydi?" sorusunu yanıtlayamadı. **Tek kaynak Git
olsaydı:** değer `lumix-config`'te, `kubectl edit` yasak (ArgoCD selfHeal geri alır),
"doğru değer" her zaman `git show`.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Değerin `git push`'tan pod'a yolculuğu

```
Geliştirici                lumix-config repo         ArgoCD (repo-server)        Downstream cluster
    │                            │                          │                          │
    │  values.yaml düzelt + PR   │                          │                          │
    ├───────────────────────────►                          │                          │
    │        merge (main)        │   izler (poll/webhook)   │                          │
    │                            ├──────────────────────────►  helm template          │
    │                            │                          │  (chart OCI + values)    │
    │                            │                          ├──────────► ConfigMap +   │
    │                            │                          │            Deployment... │
    │                            │                          │            apply ────────►
    │                            │                          │                          │  Reloader
    │                            │                          │                          │  checksum değişti
    │                            │                          │                          │  → rollout restart
    │                            │                          │                          │  pod yeni env'le açılır
```

Kimse cluster'a elle dokunmaz; **tek girdi Git commit**'tir.

### 3.2. Katmanlı values hiyerarşisi (overlay)

Tek değer birden çok yerde tekrarlanmaz; **aşağı indikçe özelleşir**, sonraki bir
öncekini ezer ([Helm values overlay](../infra-devops/03-helm-charts.md#42-values-overlay-hiyerarşisi)):

```
1) chart/values.yaml            (dev-friendly minimal default — chart içinde, OCI'de)
        ▼  ezer
2) base/values-base.yaml        (tüm servisler için Lumix standardı: probe, log, security)
        ▼  ezer
3) tiers/values-tier-{xs,s,m,l}.yaml   (boyut: replica, resource, pool size)
        ▼  ezer
4) installations/<id>/values.yaml      (müşteriye özel: image tag, region, feature flag)
        ▼  ezer
5) ApplicationSet generator            (cluster-spesifik: installation-id, registry)
```

Kural: **bir değer, ait olduğu en genel katmanda bir kez** yazılır. Tüm servislerde
aynıysa → `base/`. Sadece "m" boyutundaysa → `tiers/`. Sadece bir müşteride → o
müşterinin klasöründe. Bu, "11 dosyada tekrar" acısını kökten keser.

### 3.3. Sırsız → ConfigMap, sır → Vault (kesin ayrım)

`lumix-config`'teki hiçbir değer sır **değildir**. Akış ikiye ayrılır:

- **Sırsız değer** → Helm `configmap.yaml` template'i → `envFrom: configMapRef` → pod env.
- **Sır** → chart `externalsecret.yaml` template'i → ESO Vault'tan çeker → K8s Secret →
  `envFrom: secretRef` → pod env.

Pod'un içinde ikisi de aynı görünür (env değişkeni); ama biri Git'te açık, diğeri asla
Git'e girmez. Bu ayrım denetim (audit) ve güvenlik için kritiktir.

### 3.4. ConfigMap değişince pod'u yenilemek — Reloader

ConfigMap güncellenince Kubernetes çalışan pod'a **yeni değeri otomatik yansıtmaz**
(env'ler pod başlangıcında okunur). Çözüm iki katmanlı:

1. Helm deployment template'i ConfigMap içeriğinin hash'ini pod annotation'ına yazar
   (`checksum/config: {{ ... | sha256sum }}` — [mevcut deployment.yaml'de var](../infra-devops/03-helm-charts.md#63-academic-servicetemplatesdeploymentyaml)).
   İçerik değişince hash değişir → yeni ReplicaSet → rolling restart.
2. Chart'ın kapsamadığı harici ConfigMap'ler için **Stakater Reloader**: ilgili
   Deployment'a `reloader.stakater.com/auto: "true"` annotation'ı; Reloader ConfigMap/Secret
   değişimini izleyip `rollout restart` tetikler.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Repo: `lumix-config` (tek kaynak)

Uygulama kodundan, Helm chart'tan (OCI) ve ArgoCD Application manifest'lerinden **ayrı**,
tek sorumluluğu konfigürasyon olan bir repo:

```
gitlab.lumix.io/platform/lumix-config/
├── base/
│   └── values-base.yaml            # tüm servisler: probe, log formatı, security context
├── tiers/
│   ├── values-tier-xs.yaml
│   ├── values-tier-s.yaml
│   ├── values-tier-m.yaml
│   └── values-tier-l.yaml
├── services/                       # servise özel (müşteriden bağımsız) non-default'lar
│   ├── academic-service.yaml
│   └── finance-service.yaml
├── installations/                  # müşteri başına — TÜM servislerin overlay'i
│   ├── omer-okullari/
│   │   ├── values.yaml
│   │   └── profile.yaml            # ApplicationSet generator metadata (tier, region)
│   └── x-vakfi/
│       ├── values.yaml
│       └── profile.yaml
├── CODEOWNERS
└── README.md
```

> **Not:** Bu repo, [ArgoCD dokümanındaki](./04-argocd-gitops.md#44-repo-organizasyonu)
> `argocd-apps` repo'sunun **values/** kısmının olgunlaşmış, tek-sorumluluğa ayrılmış
> hâlidir. ArgoCD **Application/ApplicationSet manifest'leri** `argocd-apps`'te kalır;
> **değerler** `lumix-config`'te toplanır. İkisi ArgoCD **multi-source** (`ref: values`)
> ile birleşir. Deployment repo'su ile config repo'sunu ayırmak, values değişiminin
> Application CRD'sini tetiklemeden yalın PR review'dan geçmesini sağlar.

### 4.2. Katman kuralları (kesin)

- Değer **ait olduğu en genel katmanda** yazılır; alt katman sadece **farkı** yazar.
- Chart default'u **minimal ve dev-friendly** kalır ([Helm tuzağı: şişman values](../infra-devops/03-helm-charts.md#7-dikkat-edilecek-tuzaklar)).
- Katman sırası ArgoCD'de `valueFiles` sırasıyla verilir; **sonraki öncekini ezer**.
- `installations/<id>/values.yaml` içinde sub-chart prefix'i zorunlu:
  `academic-service:` altında o servisin değerleri.

### 4.3. ArgoCD ile bağlanış (multi-source)

ApplicationSet, chart'ı OCI'den, değerleri `lumix-config`'ten çeker:

```yaml
source:                                   # (ApplicationSet template içinden)
  - repoURL: oci://registry.lumix.io/charts
    chart: lumix-platform
    targetRevision: 2026.04.0
    helm:
      valueFiles:
        - $values/base/values-base.yaml
        - $values/tiers/values-tier-{{.tier}}.yaml
        - $values/installations/{{.name}}/values.yaml
  - repoURL: https://gitlab.lumix.io/platform/lumix-config.git
    targetRevision: main
    ref: values                           # yukarıdaki $values buna işaret eder
```

Detay ve generator'lar: [ArgoCD GitOps](./04-argocd-gitops.md#35-applicationset--multi-cluster).

### 4.4. Hangi değer nereye gider? (karar tablosu)

| Değer örneği | Katman | Neden |
|---|---|---|
| `management.endpoints...` (actuator exposure) | `base/` | Tüm servislerde aynı standart |
| `resources.requests.cpu`, replica sayısı | `tiers/` | Boyuta göre değişir, müşteriye göre değil |
| `academic-service` topic adı | `services/academic-service.yaml` | Servise özel, müşteriden bağımsız |
| `image.tag`, `region`, feature flag | `installations/<id>/` | Müşteriye özel |
| DB parolası, JWT secret | **hiçbiri — Vault** | Sır; asla Git'e girmez |

### 4.5. Değişiklik akışı (governance)

1. `lumix-config`'te ilgili katman dosyasını düzenle → **MR aç** (CODEOWNERS review).
2. Merge sonrası ArgoCD `main`'i görür → etkilenen installation'ları sync eder.
3. ConfigMap değişir → checksum/Reloader → ilgili pod'lar rolling restart.
4. Hatalıysa: `git revert` → ArgoCD eski hâli geri sync eder (rollback = Git işlemi).

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Spring Cloud Config Server** | Ayrı bir HA servis; her müşteri cluster'ında yeni SPOF + bakım yükü. ConfigMap/GitOps ile işlev çakışması (iki config mekanizması). K8s-native dünyada geriliyor. **Tek gerçek avantajı** — `@RefreshScope` ile redeploy'suz canlı refresh — bugün bir gereksinim değil. Gerekirse **sadece o katman** için ileride eklenebilir (bkz. tekrar değerlendirme). |
| **Consul KV / etcd (dinamik config)** | Çok-dilli dinamik config için güçlü ama biz tek-dilli (JVM) + immutable deploy tercihindeyiz; ekstra stateful bileşen. Feature flag ihtiyacı doğarsa **Unleash** daha hedefli. |
| **Config'i servis kodunda tutmak (`application-prod.yml` içinde gerçek değerler)** | Ortam bilgisi koda sızar; her değer değişikliği yeni image build; multi-tenant'ta imkânsız. [Felsefeye](../24-codebase-guide/04-konfigurasyon-ve-calistirma.md#1-konfigürasyon-felsefesi-koda-değil-ortama-bağla) aykırı. |
| **Her servis + her müşteri için ayrı repo/dosya (merkezileştirmemek)** | N×M dosya patlaması; ortak değer tekrarı; "doğru değer nerede?" belirsizliği — bu sayfanın çözdüğü acının ta kendisi. |
| **Config'i deployment repo'suyla (`argocd-apps`) tam birleştirmek** | Application CRD değişiklikleri ile gündelik values değişiklikleri aynı PR akışında karışır; review gürültüsü. Ayrı repo + multi-source daha temiz. |

### Kabul ettiğimiz trade-off'lar

- **Redeploy'suz canlı refresh yok** → config değişimi rolling restart gerektirir.
  Bedel düşük: `maxUnavailable: 0` ile kesintisiz rollout zaten var.
- **İki repo (`argocd-apps` + `lumix-config`) + multi-source** → biraz daha kurulum
  karmaşıklığı; net sorumluluk sınırı ile telafi edilir.
- **Katman disiplini insan sorumluluğu** → yanlış katmana yazılan değer tekrarı geri
  getirir. Mitigasyon: CODEOWNERS review + `README` karar tablosu.

### Tekrar değerlendirme tetikleyicileri

- **Runtime feature flag / A-B test** gerçek ihtiyaç olursa → sadece o katman için
  **Unleash** veya **Spring Cloud Config + Bus**; tüm config için değil.
- Config değişim sıklığı rolling restart'ı pahalı kılacak kadar artarsa → hot-reload
  stratejisi yeniden değerlendirilir.
- Çok-dilli servis eklenirse (JVM dışı) → dil-agnostik dinamik config (Consul) gündeme gelir.

## 6. Pratik örnek

### 6.1. `base/values-base.yaml`

```yaml
# Tüm servislerde geçerli Lumix standardı
env:
  SPRING_PROFILES_ACTIVE: prod
  JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

podSecurityContext:
  runAsNonRoot: true
  seccompProfile:
    type: RuntimeDefault

probes:
  liveness:  { path: /actuator/health/liveness,  port: mgmt }
  readiness: { path: /actuator/health/readiness, port: mgmt }

monitoring:
  serviceMonitor:
    enabled: true
    interval: 30s
```

### 6.2. `tiers/values-tier-m.yaml`

```yaml
# "m" boyutu — orta ölçekli müşteri
replicaCount: 3
resources:
  requests: { cpu: 300m, memory: 768Mi }
  limits:   { cpu: 1500m, memory: 1536Mi }
autoscaling:
  minReplicas: 2
  maxReplicas: 8
env:
  DB_POOL_SIZE: "20"
```

### 6.3. `installations/omer-okullari/values.yaml`

```yaml
installation:
  id: omer-okullari
  tier: m
  region: tr-istanbul

academic-service:
  image:
    tag: "1.4.2"
  env:
    DOMAIN_EVENTS_TOPIC: omer.academic.domain-events

finance-service:
  enabled: true
  env:
    FEATURE_INSTALLMENT_PLAN: "true"    # müşteriye özel feature flag (sırsız)
```

### 6.4. `installations/omer-okullari/profile.yaml` (ApplicationSet generator girdisi)

```yaml
name: omer-okullari
tier: m
region: tr-istanbul
```

### 6.5. ConfigMap üreten chart template'i (referans)

```yaml
# academic-service/templates/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "academic-service.fullname" . }}-config
  labels:
    {{- include "academic-service.labels" . | nindent 4 }}
data:
  {{- range $k, $v := .Values.env }}
  {{ $k }}: {{ $v | quote }}
  {{- end }}
```

Deployment bunu `envFrom: configMapRef` ile mount eder ve `checksum/config` annotation'ı
ile içerik değişince restart olur ([deployment.yaml](../infra-devops/03-helm-charts.md#63-academic-servicetemplatesdeploymentyaml)).

### 6.6. Harici ConfigMap için Reloader

```yaml
# Chart'ın üretmediği bir ConfigMap'i izlettirmek
metadata:
  annotations:
    reloader.stakater.com/auto: "true"
```

### 6.7. Lokal doğrulama

```bash
# Render et — hangi değer hangi katmandan geldi, gör
helm template lumix-platform oci://registry.lumix.io/charts/lumix-platform \
  --version 2026.04.0 \
  -f base/values-base.yaml \
  -f tiers/values-tier-m.yaml \
  -f installations/omer-okullari/values.yaml \
  | grep -A20 'kind: ConfigMap'
```

## 7. Dikkat edilecek tuzaklar

- **Sırrı `lumix-config`'e yazmak** — Git geçmişine sızar; kalıcıdır. Parola/token
  **yalnızca Vault + ExternalSecret**. Repo'ya `gitleaks` pre-commit / CI taraması koy.
- **Değeri yanlış katmana yazmak** — tüm müşterilerde aynı olan değeri `installations/`
  altına koymak tekrarı geri getirir. Karar tablosuna (4.4) uy.
- **`kubectl edit configmap`** — anında drift; ArgoCD selfHeal geri alır veya chart
  upgrade eski hâle yazar. Cluster **yalnızca Git'ten** değişir.
- **Reloader/checksum yokken config değiştirmek** — ConfigMap güncellenir ama pod eski
  değeri okumaya devam eder; "değiştirdim ama etkisi yok" tuzağı.
- **Sub-chart prefix'i unutmak** — `installations/` values'ta `academic-service:` prefix'i
  olmadan yazılan değer sessizce ignore edilir (umbrella chart kuralı).
- **Chart versiyonu bump etmeden değer değiştirmek** — ArgoCD chart'ı cache'leyebilir;
  ama values repo'su değişince yeni sync tetiklenir. Yine de chart yapısı değiştiyse
  [SemVer bump](./05-helm-versioning.md) şart.
- **`lumix-config` ve `argocd-apps`'i karıştırmak** — Application manifest'i `argocd-apps`'te,
  değerler `lumix-config`'te. Sınırı koru; yoksa review gürültüsü ve döngüsel bağımlılık.
- **Katman dosyalarını şişirmek** — chart default'u minimal kalmalı; ortak olmayan her
  şey uygun overlay katmanına.

## 8. Diğer konularla ilişkisi

- [Konfigürasyon & Çalıştırma](../24-codebase-guide/04-konfigurasyon-ve-calistirma.md) — `application.yml` şablonu + `${VAR:default}` kalıbı (bu repo o boşlukları doldurur)
- [Helm Charts](../infra-devops/03-helm-charts.md) — values overlay hiyerarşisi ve ConfigMap/ExternalSecret template'leri
- [ArgoCD GitOps](./04-argocd-gitops.md) — bu repo'yu izleyip render/apply eden controller, multi-source, ApplicationSet
- [Helm Versioning](./05-helm-versioning.md) — chart vs values değişikliğinin promotion'ı
- [External Secrets / Vault](../security-compliance/) — sırların config'e girmeden yönetimi
- [ADR-007: Merkezi Config Repo](../adr/0007-central-config-repo-gitops.md) — bu kararın kaydı ve elenen alternatifler

## 9. Daha derine inmek için

- OpenGitOps prensipleri: [https://opengitops.dev/](https://opengitops.dev/)
- Stakater Reloader: [https://github.com/stakater/Reloader](https://github.com/stakater/Reloader)
- ArgoCD multi-source apps: [https://argo-cd.readthedocs.io/en/stable/user-guide/multiple_sources/](https://argo-cd.readthedocs.io/en/stable/user-guide/multiple_sources/)
- "GitOps and Kubernetes" — Yuen, Surdilovic, Wright
- Neden Spring Cloud Config yerine GitOps: *"spring cloud config vs kubernetes configmap gitops"*
- Search keyword'leri: *"helm values overlay hierarchy"*, *"argocd multi source values repo"*, *"config as code kubernetes"*, *"stakater reloader configmap restart"*

## 10. Sözlük

- **Config-as-Code**: Konfigürasyonu versiyonlu, review'lı kod olarak Git'te tutma disiplini.
- **`lumix-config`**: Tüm ortam/müşteri sırsız konfigürasyonunun tek kaynağı olan Git deposu.
- **Values overlay**: Katmanlı Helm values dosyaları; sonraki öncekini ezer.
- **Tier**: Müşteri boyut sınıfı (xs/s/m/l) — replica/resource belirler.
- **Installation**: Bir müşteri kurulumu; `installation-id` ile etiketlenir.
- **Multi-source Application**: ArgoCD'de chart'ı bir kaynaktan, values'ı başka repo'dan çeken uygulama.
- **`ref: values`**: Multi-source'ta values repo'suna verilen ad; `$values/...` ile referans edilir.
- **ConfigMap**: Sırsız key-value config'i pod'a env/dosya olarak veren K8s kaynağı.
- **ExternalSecret**: Vault'taki sırrı K8s Secret'ına senkronlayan ESO kaynağı.
- **Reloader**: ConfigMap/Secret değişince ilgili Deployment'ı yeniden başlatan controller.
- **`checksum/config`**: ConfigMap içeriğinin hash'ini pod annotation'ına yazıp değişince restart tetikleyen kalıp.
- **Drift**: Cluster'daki gerçek durum ile Git'teki istenen durum farkı.
