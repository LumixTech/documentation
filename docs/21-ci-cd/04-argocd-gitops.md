---
title: ArgoCD — GitOps Deployment
description: ArgoCD nedir, GitOps modeli (Git = source of truth), Application CRD, ApplicationSet (multi-cluster Lumix kullanımı), sync policy (auto vs manual), rollback.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

Lumix'te K8s cluster'larına yapılan **her değişiklik Git'ten** geçer; doğrudan `kubectl apply` yasak. Bu disipline **GitOps** denir; otomasyonunu **ArgoCD** sağlar. Bu sayfa GitOps prensiplerini sıfırdan anlatır, ArgoCD'nin **Application + ApplicationSet** CRD'lerini gösterir, Lumix'in **müşteri başına cluster + tek Git repo + ApplicationSet generator** modelini detaylandırır, **sync policy (auto/manual)**, **selfHeal**, **prune**, **sync waves**, **rollback** akışlarını açıklar. Hedef kitle: K8s ve Helm temellerini bilen ([Kubernetes Temelleri](../infra-devops/01-kubernetes-fundamentals.md), [Helm Charts](../infra-devops/03-helm-charts.md)) DevOps; CI/CD'ye yeni giren mühendis.

## 1. Bu nedir? (Sıfırdan)

**GitOps** dört prensip:
1. **Declarative**: tüm istenen durum kod olarak (manifest, Helm chart).
2. **Versioned + Immutable**: Git history.
3. **Pulled automatically**: bir agent (controller) Git'i izler.
4. **Continuously reconciled**: gerçek durum hedeften saparsa otomatik düzeltilir.

**ArgoCD**, K8s için GitOps controller'ı (CNCF graduated). Tipik akış:
1. Geliştirici Helm chart values'ını Git'te değiştirir → PR → merge.
2. ArgoCD repo'yu izler, fark görür, cluster'a apply eder.
3. Eğer biri elle `kubectl edit` ile değişiklik yaparsa ArgoCD otomatik geri alır (selfHeal).

### Günlük hayattan analoji

Konutta otomatik termostat: termostat (Git) "22°C olsun" der. Klima (ArgoCD) sürekli oda sıcaklığını ölçer, fark varsa düzeltir. Birisi pencereyi açarsa (drift) klima daha çok çalışır, hedef korunur.

### Push vs Pull deployment

| Model | Açıklama | Risk |
|---|---|---|
| **Push** (Jenkins → cluster) | CI pipeline cluster'a kubectl yapıyor | Cluster credential CI'da; sızıntı patlar; drift gözden kaçar |
| **Pull** (ArgoCD → Git) | Cluster içindeki agent Git'i okur | Cluster credential dışarı çıkmaz; reconciliation sürekli |

Lumix kararı: **Pull GitOps** (ArgoCD).

## 2. Hangi problemi çözüyor?

| Acı | GitOps yok | GitOps var |
|---|---|---|
| "Production'da ne deploy'lu?" | Belirsiz | `git log` + ArgoCD UI |
| Audit "kim ne zaman deploy etti" | Slack tarama | Git commit + ArgoCD event |
| Drift (manuel değişiklik) | İz bırakmaz | ArgoCD diff + selfHeal |
| Rollback | "Önceki image'ı bul, manuel deploy" | `argocd app rollback` |
| Multi-cluster | Her cluster ayrı pipeline | ApplicationSet ile tek template |
| Disaster recovery (cluster restore) | Pipeline yeniden başlat | ArgoCD Git'ten otomatik sync |
| Yeni cluster bootstrap | Manuel apply prosedürü | ArgoCD'ye Application kayıt |

### Patlamış üretim hikayesi

Bir takım CI'dan `kubectl apply` ile deploy yapıyordu. Bir mühendis acil patch için manuel `kubectl edit deploy ...` yaptı. 3 hafta sonra "neden bu config farklı?" sorusu cevapsız. ArgoCD selfHeal ile manuel değişim 30 saniyede geri alınır (veya alert tetiklenir).

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Bileşenler

```
┌────────────────────────────────────────────┐
│  ArgoCD (lumix-internal cluster)           │
│                                            │
│  • argocd-server (API, UI)                 │
│  • argocd-repo-server (Git fetch + render) │
│  • argocd-application-controller           │
│      (sync + reconciliation loop)          │
│  • argocd-dex-server (SSO)                 │
│  • redis (cache)                           │
│  • argocd-applicationset-controller        │
└──────────────────────┬─────────────────────┘
                       │ HTTPS/kubeconfig
       ┌───────────────┼───────────────┐
       ▼               ▼               ▼
   omer-okullari    x-vakfi         y-okul
   downstream       downstream       downstream
   cluster          cluster          cluster
```

ArgoCD **lumix-internal cluster**'da yaşar; **downstream cluster'lara** (müşteri cluster'ları) kubeconfig ile erişir. Cluster kayıt: `argocd cluster add`.

### 3.2. Application CRD

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: lumix-platform-omer-okullari
  namespace: argocd
spec:
  project: lumix-customers
  source:
    repoURL: oci://registry.lumix.io/charts
    chart: lumix-platform
    targetRevision: 2026.04.0
    helm:
      releaseName: lumix-platform
      valueFiles: [$values/customers/omer-okullari/values.yaml]
  destination:
    name: c-omer-okullari
    namespace: lumix-app
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

Alanlar:
- **`source`**: Helm chart, Git repo path, Kustomize ya da plain manifest.
- **`destination`**: Hedef cluster + namespace.
- **`syncPolicy.automated`**: Git değişimini otomatik uygula.
- **`prune`**: Git'ten silinen kaynaklar cluster'dan da silinir.
- **`selfHeal`**: Manuel drift'i otomatik geri al.

### 3.3. Sync waves

Bazı kaynaklar diğerlerinden önce gelmeli (CRD önce, kullanan kaynak sonra). Annotation ile sıra:

```yaml
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "-1"   # önce
    argocd.argoproj.io/sync-wave: "0"    # default
    argocd.argoproj.io/sync-wave: "5"    # son
```

### 3.4. Sync options

```yaml
syncOptions:
  - CreateNamespace=true
  - PruneLast=true
  - ServerSideApply=true
  - ApplyOutOfSyncOnly=true
  - RespectIgnoreDifferences=true
```

### 3.5. ApplicationSet — multi-cluster

ApplicationSet, **template + generator**'dan otomatik Application'lar üretir.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: lumix-platform
  namespace: argocd
spec:
  generators:
    - clusters:
        selector:
          matchLabels:
            lumix.io/tier: m
  template:
    metadata:
      name: 'lumix-platform-{{name}}'
    spec:
      project: lumix-customers
      source:
        repoURL: oci://registry.lumix.io/charts
        chart: lumix-platform
        targetRevision: 2026.04.0
        helm:
          valueFiles: ['$values/customers/{{name}}/values.yaml']
      destination:
        name: '{{name}}'
        namespace: lumix-app
      syncPolicy:
        automated: { prune: true, selfHeal: true }
```

ArgoCD `lumix.io/tier=m` etiketli her cluster için ayrı Application üretir. Yeni müşteri eklemek: cluster register + tier label → ApplicationSet otomatik oluşturur.

### 3.6. Generator türleri

| Generator | Kullanım |
|---|---|
| `clusters` | Kayıtlı cluster listesi |
| `list` | Statik liste |
| `git` (directories) | Git'teki bir klasördeki her sub-dir bir app |
| `git` (files) | YAML dosyalarını parse |
| `matrix` | İki generator'ı çarpım (örn. cluster × env) |
| `merge` | İki generator'ı birleştir |
| `pullRequest` | Open PR başına preview env |
| `scmProvider` | GitLab/GitHub org'daki tüm repo'lar |

### 3.7. Health check

ArgoCD pod/deployment/StatefulSet sağlığını **Lua script** ile değerlendirir. Helm install + sync OK ama pod CrashLoop → Application "Degraded" durumda. Custom resource (CRD) için Lua kayıtlı health script.

### 3.8. Rollback

```bash
argocd app history lumix-platform-omer-okullari
# ID  DATE                  REVISION
# 13  2026-05-27 14:30:00   2026.04.0
# 12  2026-05-26 11:20:00   2026.03.5
# 11  ...

argocd app rollback lumix-platform-omer-okullari 12
```

Veya UI'dan "Rollback" tıklayarak.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Yer

Lumix-internal cluster'da `argocd` namespace.

```bash
helm install argocd argo/argo-cd \
  --namespace argocd \
  --create-namespace \
  --version 7.0.0 \
  -f values-argocd.yaml
```

`values-argocd.yaml`:

```yaml
server:
  ingress:
    enabled: false   # Traefik ayrı manage
  config:
    url: https://argocd.lumix.io
    oidc.config: |
      name: Keycloak
      issuer: https://keycloak.lumix.io/realms/lumix-staff
      clientID: argocd
      clientSecret: $oidc.keycloak.clientSecret
      requestedScopes: ["openid", "profile", "email", "groups"]

repoServer:
  replicas: 3
  resources:
    requests: { cpu: 200m, memory: 512Mi }

controller:
  replicas: 2
  resources:
    requests: { cpu: 500m, memory: 1Gi }

dex:
  enabled: false   # OIDC direkt; Dex bypass

redis-ha:
  enabled: true
  replicas: 3

applicationSet:
  replicas: 2

configs:
  cm:
    timeout.reconciliation: 60s
    timeout.hard.reconciliation: 60m
    application.instanceLabelKey: argocd.argoproj.io/instance
  params:
    server.insecure: true       # Traefik TLS terminate
```

### 4.2. Cluster kayıt

Yeni müşteri cluster'ı Rancher import sonrası:
```bash
argocd cluster add c-omer-okullari --kubeconfig ~/.kube/config --label lumix.io/tier=m --label lumix.io/installation-id=omer-okullari
```

Cluster credential ArgoCD `argocd-secret` Secret'larında (encrypted).

### 4.3. Project organizasyonu

```
ArgoCD Projects:
  ├── lumix-platform          (Lumix-internal: ArgoCD, Vault, GitLab — self-managed)
  ├── lumix-customers         (tüm müşteri application'ları)
  └── lumix-shared            (cross-cluster addons)
```

Project sourceRepos, destinations, allowed namespaces tanımlar (RBAC):

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name: lumix-customers
  namespace: argocd
spec:
  sourceRepos:
    - 'oci://registry.lumix.io/charts'
    - 'https://gitlab.lumix.io/platform/argocd-apps.git'
  destinations:
    - server: '*'
      namespace: 'lumix-*'
  clusterResourceWhitelist:
    - group: ''
      kind: Namespace
    - group: 'cert-manager.io'
      kind: ClusterIssuer
  namespaceResourceWhitelist:
    - group: '*'
      kind: '*'
  roles:
    - name: customer-cluster-admin
      policies:
        - p, proj:lumix-customers:customer-cluster-admin, applications, *, lumix-customers/*, allow
      groups:
        - lumix-customer-admins
```

### 4.4. Repo organizasyonu

Tek "argocd-apps" repo:

```
gitlab.lumix.io/platform/argocd-apps/
├── applicationsets/
│   ├── lumix-platform-tier-m.yaml
│   ├── lumix-platform-tier-l.yaml
│   └── cluster-addons.yaml
├── customers/
│   ├── omer-okullari/
│   │   └── values.yaml
│   ├── x-vakfi/
│   └── y-okul/
├── projects/
│   ├── lumix-customers.yaml
│   └── lumix-platform.yaml
└── README.md
```

Bu repo'nun kendisi de "app-of-apps" pattern'le ArgoCD tarafından yönetilir → meta-bootstrap.

### 4.5. Sync policy

Lumix kararları:
- **`automated.prune: true`**: Git'ten silinen Cluster'dan da gider (declarative tutarlılık).
- **`automated.selfHeal: true`**: drift düzeltilir.
- **`SyncOptions: ServerSideApply=true`**: K8s server-side apply (büyük resource için).
- **`retry.limit: 5`** + exponential backoff.

Production seviyesi: **otomatik sync** (devops'un manuel onayı CI tarafında, Git merge'i = onay).

### 4.6. Sync wave kullanımı

```yaml
# CRD'ler önce
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "-10"
---
# Namespace ve RBAC
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "-5"
---
# ExternalSecret'lar (Vault'tan secret çekmek)
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "-3"
---
# Veritabanı, Kafka StatefulSet
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "0"
---
# Microservice'ler
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "5"
---
# Kong / Traefik (en son trafik açılır)
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "10"
```

### 4.7. Image updater (opsiyonel)

ArgoCD Image Updater: registry'deki yeni image tag'i otomatik Git'e PR atar. Lumix tercihi: **kapalı**. Lumix Git PR + tag-based release tercih.

### 4.8. Notifications

ArgoCD Notifications (`argocd-notifications-controller`) sync sonucu Slack/email'e gönderir:

```yaml
trigger.on-deployed:
  - description: Application is synced and healthy
    send: [slack-deploy-channel]
    when: app.status.operationState.phase in ['Succeeded']

template.slack-deploy-channel:
  message: |
    {{.app.metadata.name}} synced!
    Revision: {{.app.status.sync.revision}}
    Cluster: {{.app.spec.destination.name}}
```

### 4.9. Drift alert

ArgoCD selfHeal otomatik düzeltir; ama Lumix audit istiyor. `argocd-drift-alert` cronjob: 1 saatte bir tüm Application'ları `argocd app diff` ile karşılaştırır, drift varsa Slack alert.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Flux v2** | Çok güçlü, ArgoCD ile birinci aday. ArgoCD UI Lumix için daha kullanışlı; ApplicationSet generator olgun. Flux: Lumix değerlendirdi, ArgoCD seçti. |
| **Rancher Fleet** | Lumix Fleet'i sadece cluster addon dağıtımı için kullanır (Rancher entegre). Application GitOps ArgoCD'de. İki controller arası sınır net. |
| **Jenkins X** | Daha geniş ekosistem ama karmaşık; Tekton + Knative bağımlılıkları. |
| **Helmfile (push)** | Push model; GitOps prensiplerini ihlal eder. |
| **Spinnaker** | Multi-cloud deploy ama K8s-spesifik GitOps değil. |
| **CD Foundation araçları** | Çok yaygın değil. |

### Kabul ettiğimiz trade-off'lar

- **İki GitOps aracı (ArgoCD + Fleet)**: net sorumluluk paylaşımı gerekir; doc + ekip eğitimi.
- **ArgoCD lumix-internal cluster'da single point of failure**: replica 2-3, ama cluster çökerse ArgoCD UI yok. Müşteri cluster'larında ArgoCD agent yok (centralized model).
- **Çok büyük müşteri sayısında ArgoCD memory'si**: 100+ Application'da repo-server tuning gerek.

### Tekrar değerlendirme tetikleyicileri

- Müşteri sayısı 200+ olunca ArgoCD'nin ölçeklenmesi: federated ArgoCD veya per-cluster ArgoCD instance.
- Flux'un olgunluk hızı ArgoCD'yi geçerse yeniden değerlendirme.

## 6. Pratik örnek

### 6.1. App-of-apps bootstrap

```yaml
# root-app.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: root
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://gitlab.lumix.io/platform/argocd-apps.git
    targetRevision: main
    path: applicationsets/
    directory:
      recurse: true
  destination:
    server: https://kubernetes.default.svc
    namespace: argocd
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

Bu tek Application bütün ApplicationSet'leri uygular → bunlar tüm müşteri uygulamalarını ekler.

### 6.2. ApplicationSet — tier-m müşteriler

```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: lumix-platform-tier-m
  namespace: argocd
spec:
  goTemplate: true
  generators:
    - matrix:
        generators:
          - clusters:
              selector:
                matchLabels:
                  lumix.io/tier: m
          - git:
              repoURL: https://gitlab.lumix.io/platform/argocd-apps.git
              revision: main
              files:
                - path: 'customers/{{.name}}/profile.yaml'
  template:
    metadata:
      name: 'lumix-platform-{{.name}}'
      labels:
        lumix.io/installation: '{{.name}}'
    spec:
      project: lumix-customers
      source:
        repoURL: oci://registry.lumix.io/charts
        chart: lumix-platform
        targetRevision: 2026.04.0
        helm:
          releaseName: lumix-platform
          valueFiles:
            - $values/customers/{{.name}}/values.yaml
        - repoURL: https://gitlab.lumix.io/platform/argocd-apps.git
          targetRevision: main
          ref: values
      destination:
        name: '{{.name}}'
        namespace: lumix-app
      syncPolicy:
        automated:
          prune: true
          selfHeal: true
        syncOptions:
          - CreateNamespace=true
          - ServerSideApply=true
        retry:
          limit: 5
          backoff:
            duration: 30s
            factor: 2
            maxDuration: 5m
```

### 6.3. Rollback komutu

```bash
argocd login argocd.lumix.io --sso

argocd app history lumix-platform-omer-okullari
# ID  REVISION
# 13  2026.04.0
# 12  2026.03.5

argocd app rollback lumix-platform-omer-okullari 12

argocd app wait lumix-platform-omer-okullari --health
```

### 6.4. Drift simulation

```bash
# Bir kaynağı manuel değiştir
kubectl --context c-omer-okullari -n lumix-app scale deploy/academic-service --replicas=10

# ArgoCD diff
argocd app diff lumix-platform-omer-okullari
# - replicas: 3
# + replicas: 10

# selfHeal sayesinde 30-60s içinde geri alınır
kubectl --context c-omer-okullari -n lumix-app get deploy academic-service
# replicas: 3
```

### 6.5. Notifications örnek

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: argocd-notifications-cm
  namespace: argocd
data:
  service.slack: |
    token: $slack-token
  trigger.on-sync-failed: |
    - send: [slack-deploy-failures]
      when: app.status.operationState.phase in ['Failed', 'Error']
  template.slack-deploy-failures: |
    message: |
      :red_circle: Sync failed
      App: {{.app.metadata.name}}
      Cluster: {{.app.spec.destination.name}}
      Error: {{.app.status.operationState.message}}
  subscriptions: |
    - recipients: [slack:deploy-alerts]
      triggers: [on-sync-failed]
```

### 6.6. CI'dan ArgoCD'ye trigger

```yaml
deploy-prod:
  script:
    - argocd login argocd.lumix.io --auth-token $ARGOCD_AUTH_TOKEN --grpc-web
    - argocd app set lumix-platform-omer-okullari -p academic-service.image.tag=${CI_COMMIT_TAG}
    - argocd app sync lumix-platform-omer-okullari --timeout 600
    - argocd app wait lumix-platform-omer-okullari --health --timeout 600
```

Bu pipeline Git'i bypass etmiyor; sadece Helm values parametresini ArgoCD üzerinden update ediyor. Detay: `app set -p` aslında Application CRD'yi günceller; CRD ArgoCD repo'sundan da yönetilebilir → ideal akış commit + auto-sync.

### 6.7. UI üzerinden inceleme

```
https://argocd.lumix.io/applications
  ├── lumix-platform-omer-okullari (Healthy + Synced)
  ├── lumix-platform-x-vakfi      (OutOfSync + Syncing)
  └── lumix-platform-y-okul       (Healthy + Synced)
```

App'e tıkla: resource tree (Deployment → ReplicaSet → Pods), her resource'a health + sync durum + manifest + logs.

## 7. Dikkat edilecek tuzaklar

- **`prune: true` + Git'te accidentally silinen kaynak**: prod'da kaynaklar siliniyor. Lumix kuralı: prune açık olsun ama prod project'lerinde `PruneLast=true` + dikkatli MR review.
- **`selfHeal: false`** + manuel debug yapmak: temporary acceptable. Sonra mutlaka açılmalı.
- **Aynı kaynağı iki Application yönetiyor**: çakışma. Owner Application bir tane olmalı (label `argocd.argoproj.io/instance`).
- **CRD önce gelmemesi**: sync-wave ile CRD'leri `-10` koy.
- **Helm chart sub-chart'larında image tag override**: yanlış override path. `[parent].[subchart].image.tag` syntax dikkat.
- **`server.insecure: true` + Traefik termination olmayan ortam**: HTTP traffic açık. Sadece TLS terminate eden gateway arkasında.
- **OIDC config'i hatalı**: kullanıcı login olamaz ama admin password var. Acil durumda local admin yedek.
- **Repo credential cluster'da plain text**: Vault + ExternalSecret entegrasyonu.
- **`syncPolicy.automated` olmayan production app**: ArgoCD UI'da "Sync" tıklamak unutulur, drift birikir.
- **Application CRD label'ları yanlış**: ApplicationSet generator eşleşmez. Cluster register ederken doğru label.
- **`retry.limit: 100`** gibi yüksek değer: hatalı kaynak sonsuz retry → controller meşgul. Limit 5-10.
- **Image updater + Git tag-based release karışıklığı**: iki ayrı PR akışı çakışır. Lumix sadece tag-based.

## 8. Diğer konularla ilişkisi

- [Helm Charts](../infra-devops/03-helm-charts.md) — ArgoCD'nin uyguladığı paket
- [Helm Versioning](./05-helm-versioning.md) — `targetRevision` ne anlama gelir
- [GitLab CE Self-Hosted](./01-gitlab-ce-self-hosted.md) — Git source
- [GitLab CI Pipelines](./02-gitlab-ci-pipelines.md) — pipeline'dan ArgoCD trigger
- [Rancher Multi-Cluster](../infra-devops/04-rancher-multi-cluster.md) — cluster register + Fleet'le farkı
- [Customer Onboarding Pipeline](../20-iac-provisioning/03-customer-onboarding-pipeline.md) — ArgoCD app create adımı
- [cert-manager](../infra-devops/08-cert-manager-tls.md) — sync wave ile önce CRD

## 9. Daha derine inmek için

- Resmi doc: [https://argo-cd.readthedocs.io/](https://argo-cd.readthedocs.io/)
- ApplicationSet: [https://argocd-applicationset.readthedocs.io/](https://argocd-applicationset.readthedocs.io/)
- "Cloud Native Continuous Delivery"
- "GitOps and Kubernetes" — Yuen, Surdilovic, Wright
- OpenGitOps principles: [https://opengitops.dev/](https://opengitops.dev/)
- Search keyword'leri: *"argocd applicationset generator matrix"*, *"argocd sync wave order"*, *"argocd selfheal drift"*, *"argocd app-of-apps pattern"*, *"argocd vs flux"*

## 10. Sözlük

- **GitOps**: Git source of truth + pull-based reconciliation + declarative + continuous.
- **ArgoCD**: K8s için CNCF GitOps controller'ı.
- **Application (CRD)**: Tek bir deploy hedefi.
- **ApplicationSet (CRD)**: Application'ları otomatik üreten template + generator.
- **Generator**: ApplicationSet'in app üretmek için kullandığı kaynak (clusters, git, list, matrix).
- **AppProject**: RBAC + repo/destination policy grubu.
- **`source.targetRevision`**: Helm chart/Git tag/branch.
- **`destination`**: Hedef cluster + namespace.
- **`syncPolicy.automated.prune`**: Git'ten silinen cluster'dan da silinir.
- **`syncPolicy.automated.selfHeal`**: Manuel drift otomatik geri alınır.
- **Sync wave**: Resource uygulama sıralaması annotation'ı.
- **`ServerSideApply`**: K8s server-side apply mode (büyük resource, drift detection ile uyumlu).
- **App-of-apps**: Tek Application'ın başka Application'ları yöneten pattern.
- **Drift**: Cluster gerçek durumu ile Git'teki istenen durum farkı.
- **Reconciliation**: ArgoCD'nin Git ↔ cluster karşılaştırma + uygulama döngüsü.
- **Image Updater**: Registry'den yeni image tag'ini izleyip Git'e PR atan eklenti.
- **Notifications**: Sync sonuçlarını Slack/email'e gönderen controller.
