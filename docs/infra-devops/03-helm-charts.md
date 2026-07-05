---
title: Helm Charts
description: Helm nedir, chart yapısı, templating, values yönetimi, release lifecycle ve Lumix'in chart-per-service + umbrella chart yaklaşımı.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Lumix'te 10 microservice + 12 altyapı bileşeni × her müşteri = onlarca cluster, yüzlerce manifest. Bu manifestleri "kopyala-yapıştır" ile yönetmek 1. günde patlar. **Helm**, K8s manifest'lerini **paketlenmiş, parametreli, versiyonlu** şekilde yöneten standart aracıdır. Bu sayfa Helm'i sıfırdan anlatır, chart anatomisini gösterir, templating motorunu açıklar, release lifecycle'ını izah eder, Lumix'in **chart-per-service + umbrella chart** yaklaşımını detaylandırır ve **values overlay** stratejisini somut örneklerle gösterir. Hedef kitle: K8s temellerini bilen ([Kubernetes Temelleri](./01-kubernetes-fundamentals.md)), manifest yazımına yeni geçen geliştirici.

## 1. Bu nedir? (Sıfırdan)

Helm, **Kubernetes için paket yöneticisidir** (apt/yum/npm gibi). Üç anahtar kavramı vardır:

- **Chart**: Bir uygulamayı kuran K8s manifest **şablonlarının paketi**. İçinde `Chart.yaml`, `values.yaml`, `templates/` dizini vardır.
- **Release**: Bir chart'ın belirli `values` ile cluster'a **kurulmuş canlı kopyası**. Aynı chart farklı namespace'lerde farklı release adıyla N kez çalışabilir.
- **Repository**: Chart'ların yayınlandığı, indirilebildiği yer (HTTPS/OCI). Lumix kendi private repo'sunu (GitLab/ChartMuseum) tutar.

Helm bir manifest'i alıp **template engine** (Go templates) ile değişkenleri yerine koyar:

```yaml
# templates/deployment.yaml
spec:
  replicas: {{ .Values.replicaCount }}
  containers:
    - image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
```

```yaml
# values.yaml
replicaCount: 3
image:
  repository: registry.lumix.io/lumix/academic-service
  tag: "1.4.2"
```

`helm install academic-service ./academic-chart` komutu → değerler yerine geçer → standart K8s YAML çıkar → apiserver'a gönderilir.

### Günlük hayattan analoji

`apt install nginx` dediğinde: paket içinden config template'leri, systemd unit'leri, varsayılan değerlerle birlikte gelir. Sen birkaç parametreyi (port, virtual host) değiştirirsin. Helm: Kubernetes için bu deneyim.

## 2. Hangi problemi çözüyor?

K8s'te tek bir microservice için tipik olarak şu manifest'ler gerekir:
- `Deployment`
- `Service`
- `ConfigMap`
- `Secret` (veya ExternalSecret)
- `IngressRoute`
- `ServiceMonitor` (Prometheus)
- `HorizontalPodAutoscaler`
- `PodDisruptionBudget`
- `NetworkPolicy`
- `ServiceAccount` + `Role` + `RoleBinding`

Bu en az **10 dosya × her servis × her ortam (dev/staging/prod) × her müşteri**. Düz YAML'la yönettiğinde:

| Acı | Helm öncesi | Helm sonrası |
|---|---|---|
| Versiyon bump (1.4.2 → 1.4.3) | Tüm cluster'larda manifest düzelt | `--set image.tag=1.4.3` |
| Müşteri-özel config | Tüm cluster'larda dosya çoğalt | `values-omer-okullari.yaml` overlay |
| Atomic upgrade | "Bazı dosyalar uygulandı bazıları hata" | `helm upgrade` tek transaction |
| Rollback | "Önceki state'i hatırlamak" | `helm rollback academic-service 5` |
| Dependency | Manuel sıralama | `Chart.yaml: dependencies` |
| Lint | Yok | `helm lint` + `helm template --debug` |

### Patlamış üretim hikayesi

Bir takım K8s'i Kustomize ile yönetiyordu. 8 servis × 4 ortam × 12 müşteri = ~400 overlay dosyası. Bir feature flag eklenince **30 dosya** değişti. Patch sırası karıştı, prod'a yarısı uygulandı. Rollback 4 saat sürdü. Helm chart **tek versiyon** olsaydı: `helm upgrade --version 1.5.0` veya `helm rollback`. Lumix bu acıyı tasarımla dışlar.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Chart yapısı

```
academic-service/
├── Chart.yaml          # metadata: name, version, appVersion, dependencies
├── values.yaml         # varsayılan değerler
├── values.schema.json  # opsiyonel: values doğrulaması (JSON Schema)
├── templates/
│   ├── _helpers.tpl    # macro tanımları (include, define)
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── configmap.yaml
│   ├── ingressroute.yaml
│   ├── hpa.yaml
│   ├── pdb.yaml
│   ├── networkpolicy.yaml
│   ├── serviceaccount.yaml
│   ├── servicemonitor.yaml
│   └── NOTES.txt       # install sonrası kullanıcıya gösterilen mesaj
├── charts/             # subchart dependencies (otomatik indirilen)
├── crds/               # CRD'ler (templating dışı, ilk install'da kurulur)
└── README.md
```

### 3.2. `Chart.yaml`

```yaml
apiVersion: v2
name: academic-service
description: Lumix Academic Microservice
type: application
version: 0.4.1          # chart versiyon (SemVer)
appVersion: "1.4.2"     # uygulamanın versiyon (string)
kubeVersion: ">=1.28.0"
maintainers:
  - name: Lumix Platform Team
    email: platform@lumix.io
dependencies:
  - name: postgresql
    version: "15.5.0"
    repository: oci://registry.lumix.io/charts
    condition: postgresql.enabled
```

**Chart version vs app version**:
- `version`: **chart** (paket) versiyonu. Chart'ta yapısal bir değişiklik olursa bump edilir.
- `appVersion`: chart'ın içine paketlenen **uygulamanın** versiyonu (Docker image tag).

Detay: [Helm Versioning](../21-ci-cd/05-helm-versioning.md).

### 3.3. Templating motoru

Helm Go `text/template` kullanır. Önemli özellikler:

```yaml
# Conditional
{{- if .Values.ingress.enabled }}
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
...
{{- end }}

# Loop
{{- range .Values.env }}
- name: {{ .name }}
  value: {{ .value | quote }}
{{- end }}

# Include (macro çağrısı)
metadata:
  labels:
    {{- include "academic-service.labels" . | nindent 4 }}

# Function pipeline
image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"

# Required (eksikse install fail)
password: {{ required "DB password is required" .Values.db.password | b64enc }}

# Lookup (cluster'dan canlı veri okuma — dikkatli kullan)
{{- $existing := lookup "v1" "Secret" .Release.Namespace "academic-db" }}
```

### 3.4. `_helpers.tpl`

Tekrar eden parçalar burada tanımlanır:

```yaml
{{/* Standart Lumix label seti */}}
{{- define "academic-service.labels" -}}
app.kubernetes.io/name: {{ include "academic-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: lumix
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
lumix.io/installation-id: {{ .Values.installation.id | quote }}
lumix.io/tier: {{ .Values.installation.tier | default "standard" | quote }}
{{- end }}
```

### 3.5. Release lifecycle

```
helm install      → revision 1 (deployed)
helm upgrade      → revision 2 (deployed); revision 1 (superseded)
helm upgrade      → revision 3 (deployed); revision 2 (superseded)
helm rollback 2   → revision 4 (deployed)  ← revision 2'nin manifest kopyası
helm uninstall    → tüm revisionlar silinir (veya --keep-history ile saklanır)
```

Helm release state'i K8s'in **Secret** kaynaklarında saklanır (default: `secrets` driver). Her release × her revision için bir secret: `sh.helm.release.v1.<release>.v<n>`.

### 3.6. Hook'lar

Release lifecycle içinde özel zamanlarda çalışan template'ler:
- `pre-install` / `post-install`
- `pre-upgrade` / `post-upgrade`
- `pre-delete` / `post-delete`
- `pre-rollback` / `post-rollback`
- `test`

Lumix kullanımı: Flyway migration `pre-upgrade` Job'ı olarak.

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: "{{ include "academic-service.fullname" . }}-flyway"
  annotations:
    "helm.sh/hook": pre-upgrade,pre-install
    "helm.sh/hook-weight": "-5"
    "helm.sh/hook-delete-policy": before-hook-creation,hook-succeeded
spec:
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: flyway
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          command: ["java", "-cp", "/app/app.jar", "org.springframework.boot.loader.PropertiesLauncher"]
          args: ["--spring.profiles.active=migration"]
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Chart organizasyonu — "chart-per-service + umbrella"

```
lumix/charts/
├── identity-service/        # her servis için ayrı chart (independent versioning)
├── organization-service/
├── academic-service/
├── assessment-service/
├── counseling-service/
├── performance-service/
├── communication-service/
├── finance-service/
├── file-service/
├── audit-service/
├── compliance-service/
├── lumix-platform/          # umbrella chart: yukarıdaki 10 servis + altyapı subchart'ları
│   ├── Chart.yaml
│   ├── values.yaml          # tüm sub-chart'ların default'u
│   ├── values-omer-okullari.yaml   # müşteriye özel overlay
│   ├── values-x-vakfi.yaml
│   └── charts/              # helm dep up sonrası dolar
```

Kurallar:
- Her servis chart'ı **bağımsız versiyonlanır**.
- Umbrella chart `dependencies:` ile sub-chart versiyonlarını kilitler.
- Umbrella chart versiyonu = installation snapshot. Müşteriye `helm upgrade lumix-platform --version 2026.04.0` ile tek komutla deploy.

### 4.2. Values overlay hiyerarşisi

```
chart/values.yaml          (default — minimal, dev-friendly)
       │
       ▼
values-base.yaml           (Lumix standart: probe, security, label)
       │
       ▼
values-tier-{xs,s,m,l}.yaml  (boyut-bazlı replica/resource)
       │
       ▼
values-installation-{customer-id}.yaml  (müşteri-spesifik: image tag, license)
       │
       ▼
ArgoCD ApplicationSet generator override (env-spesifik: image registry, secret)
```

Helm `-f` ile birden fazla values dosyası verilir; **sonraki öncekini override eder**.

```bash
helm upgrade --install lumix-platform ./lumix-platform \
  -f values-base.yaml \
  -f values-tier-m.yaml \
  -f values-installation-omer-okullari.yaml \
  --namespace lumix-app \
  --create-namespace \
  --version 2026.04.0
```

### 4.3. Repository ve image

Lumix Helm chart repository = **GitLab Container Registry (OCI)**.

```bash
# Login
helm registry login registry.lumix.io -u $CI_REGISTRY_USER -p $CI_REGISTRY_PASSWORD

# Push (CI'da)
helm package academic-service
helm push academic-service-0.4.1.tgz oci://registry.lumix.io/charts

# Pull (cluster'dan)
helm pull oci://registry.lumix.io/charts/academic-service --version 0.4.1
```

### 4.4. ArgoCD ile birleşim

Lumix Helm chart'ları **doğrudan helm install** ile değil, **ArgoCD Application** üzerinden uygulanır:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: omer-okullari-platform
  namespace: argocd
spec:
  project: lumix
  source:
    repoURL: oci://registry.lumix.io/charts
    chart: lumix-platform
    targetRevision: 2026.04.0
    helm:
      releaseName: lumix-platform
      valueFiles:
        - $values/installations/omer-okullari/values.yaml
  destination:
    server: https://omer-okullari-k8s.lumix.io
    namespace: lumix-app
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

Detay: [ArgoCD GitOps](../21-ci-cd/04-argocd-gitops.md).

### 4.5. CRD yönetimi

Helm `crds/` klasörü CRD'leri **ilk install'da** kurar; sonraki upgrade'lerde **dokunmaz** (bu güvenlik için). Lumix kararı:
- Operator CRD'leri (cert-manager, traefik, ESO) **ayrı bootstrap chart**'tan kurulur.
- Application chart'ları kendi CRD'sini barındırmaz; bağımlılık olarak değerlendirilir.

### 4.6. Sızdırılmayan secret'lar

`values.yaml` Git'tedir → secret yazılmaz. Kurallar:
1. Secret değerleri **ExternalSecret CRD** ile Vault'tan çekilir.
2. Template `Secret` üretmek yerine ESO'ya referans verir.

```yaml
# templates/externalsecret.yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: {{ include "academic-service.fullname" . }}-db
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: vault-backend
    kind: ClusterSecretStore
  target:
    name: {{ include "academic-service.fullname" . }}-db
  data:
    - secretKey: password
      remoteRef:
        key: secret/data/lumix/{{ .Values.installation.id }}/academic/db
        property: password
    - secretKey: jdbcUrl
      remoteRef:
        key: secret/data/lumix/{{ .Values.installation.id }}/academic/db
        property: jdbcUrl
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Kustomize** | Patch-based overlay; kompleks dependency yönetimi yok, hook yok, release rollback yok. Helm + Kustomize karışımı yerine tek araç tercih. |
| **Jsonnet / Tanka** | Güçlü ama öğrenme eğrisi yüksek, ekosistem dar. |
| **Plain YAML + scripts** | Boilerplate katlanır, hata yüzeyi yüksek. |
| **Operator (CRD-based)** | Her uygulama için operator yazmak yerine Helm yeterli. Operator karmaşık state machine olan uygulamalar için (DB, Kafka) tercih edilir. |
| **CDK8s** | TypeScript ile manifest üretimi; geliştiriciye iyi, ops'a kapalı. Lumix ops-friendly YAML tercih eder. |

### Kabul ettiğimiz trade-off'lar

- **Go template syntax** okumayı zorlaştırabilir → `_helpers.tpl` ve `helm template` ile preview disiplini.
- **`helm install` cluster state'i** tutar (Secret) → ArgoCD ile birleşince `--dry-run` veya `--server-side` modlar gerekebilir.
- **CRD upgrade'i dokunmaz** → CRD bootstrap ayrı chart'ta.

### Tekrar değerlendirme tetikleyicileri

- Çok büyük operator-driven mimariye geçersek (DB, Kafka, Vault hep operator) → operator-pattern öne çıkabilir; Helm tek tarafta kalır.
- Helm Chart standart'ı önemli bir bozucu sürüm yayınlarsa (Helm 4 gibi) → tekrar değerlendirme.

## 6. Pratik örnek

### 6.1. `academic-service/Chart.yaml`

```yaml
apiVersion: v2
name: academic-service
description: Lumix Academic Microservice
type: application
version: 0.4.1
appVersion: "1.4.2"
kubeVersion: ">=1.28.0"
keywords: [lumix, academic, microservice]
maintainers:
  - name: Platform Team
annotations:
  artifacthub.io/changes: |
    - kind: added
      description: gRPC server port exposure
    - kind: fixed
      description: probe path corrected
```

### 6.2. `academic-service/values.yaml` (default)

```yaml
replicaCount: 2

image:
  repository: registry.lumix.io/lumix/academic-service
  pullPolicy: IfNotPresent
  tag: ""   # boşsa Chart.appVersion kullanılır

imagePullSecrets:
  - name: lumix-registry

serviceAccount:
  create: true
  annotations: {}
  name: ""

podSecurityContext:
  runAsNonRoot: true
  runAsUser: 1000
  fsGroup: 1000
  seccompProfile:
    type: RuntimeDefault

securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: [ALL]

service:
  type: ClusterIP
  http:
    port: 80
    targetPort: 8080
  grpc:
    port: 9090
    targetPort: 9090

ingress:
  enabled: false
  className: traefik
  host: ""

resources:
  requests:
    cpu: 300m
    memory: 768Mi
  limits:
    cpu: 1500m
    memory: 1536Mi

autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 8
  targetCPUUtilizationPercentage: 70
  targetMemoryUtilizationPercentage: 80

probes:
  liveness:
    path: /actuator/health/liveness
    port: 8081
  readiness:
    path: /actuator/health/readiness
    port: 8081
  startup:
    failureThreshold: 30
    periodSeconds: 5

env:
  SPRING_PROFILES_ACTIVE: prod
  JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

postgresql:
  enabled: false  # her servis kendi DB Helm release'inden alır

installation:
  id: ""
  tier: standard

monitoring:
  serviceMonitor:
    enabled: true
    interval: 30s

networkPolicy:
  enabled: true
```

### 6.3. `academic-service/templates/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "academic-service.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "academic-service.labels" . | nindent 4 }}
spec:
  replicas: {{ if .Values.autoscaling.enabled }}{{ .Values.autoscaling.minReplicas }}{{ else }}{{ .Values.replicaCount }}{{ end }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      {{- include "academic-service.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "academic-service.selectorLabels" . | nindent 8 }}
      annotations:
        checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}
    spec:
      serviceAccountName: {{ include "academic-service.serviceAccountName" . }}
      securityContext:
        {{- toYaml .Values.podSecurityContext | nindent 8 }}
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: app
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: 8080
            - name: mgmt
              containerPort: 8081
            - name: grpc
              containerPort: 9090
          envFrom:
            - configMapRef:
                name: {{ include "academic-service.fullname" . }}-config
            - secretRef:
                name: {{ include "academic-service.fullname" . }}-db
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          livenessProbe:
            httpGet:
              path: {{ .Values.probes.liveness.path }}
              port: mgmt
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: {{ .Values.probes.readiness.path }}
              port: mgmt
            periodSeconds: 5
          startupProbe:
            httpGet:
              path: {{ .Values.probes.liveness.path }}
              port: mgmt
            failureThreshold: {{ .Values.probes.startup.failureThreshold }}
            periodSeconds: {{ .Values.probes.startup.periodSeconds }}
          securityContext:
            {{- toYaml .Values.securityContext | nindent 12 }}
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
```

### 6.4. Umbrella chart `lumix-platform/Chart.yaml`

```yaml
apiVersion: v2
name: lumix-platform
type: application
version: 2026.04.0
appVersion: "1.4.2"
description: Lumix platform installation umbrella chart
dependencies:
  - name: identity-service
    version: "0.3.2"
    repository: oci://registry.lumix.io/charts
  - name: organization-service
    version: "0.3.0"
    repository: oci://registry.lumix.io/charts
  - name: academic-service
    version: "0.4.1"
    repository: oci://registry.lumix.io/charts
  - name: finance-service
    version: "0.2.5"
    repository: oci://registry.lumix.io/charts
  - name: file-service
    version: "0.2.0"
    repository: oci://registry.lumix.io/charts
  - name: audit-service
    version: "0.1.8"
    repository: oci://registry.lumix.io/charts
  - name: compliance-service
    version: "0.1.4"
    repository: oci://registry.lumix.io/charts
  - name: kong
    version: "2.40.0"
    repository: https://charts.konghq.com
  - name: traefik
    version: "26.0.0"
    repository: https://traefik.github.io/charts
```

### 6.5. Müşteri overlay: `values-omer-okullari.yaml`

```yaml
installation:
  id: omer-okullari
  tier: m
  region: tr-istanbul

academic-service:
  replicaCount: 4
  resources:
    requests:
      cpu: 500m
      memory: 1Gi
  image:
    tag: "1.4.2"

finance-service:
  enabled: true
  replicaCount: 2

kong:
  ingressController:
    enabled: true
  proxy:
    type: LoadBalancer
```

### 6.6. CI'da chart paketleme

```yaml
# .gitlab-ci.yml parçası
helm-package:
  stage: package
  image: alpine/helm:3.15.0
  script:
    - helm dependency update charts/academic-service
    - helm lint charts/academic-service
    - helm package charts/academic-service --version ${CI_COMMIT_TAG} --app-version ${CI_COMMIT_TAG}
    - helm registry login -u $CI_REGISTRY_USER -p $CI_REGISTRY_PASSWORD $CI_REGISTRY
    - helm push academic-service-${CI_COMMIT_TAG}.tgz oci://${CI_REGISTRY}/lumix/charts
  rules:
    - if: $CI_COMMIT_TAG
```

### 6.7. Lokal preview ve test

```bash
# Render et, hata yok mu?
helm template academic-service ./academic-service \
  -f values.yaml \
  -f values-omer-okullari.yaml \
  --set installation.id=omer-okullari \
  | tee /tmp/rendered.yaml

# K8s validate
kubectl apply --dry-run=server -f /tmp/rendered.yaml

# Schema validation (values.schema.json)
helm lint academic-service --strict

# Hook'ları çalıştırmadan upgrade simülasyonu
helm upgrade --install academic-service ./academic-service --dry-run --debug
```

## 7. Dikkat edilecek tuzaklar

- **Aynı release adıyla farklı namespace'lere kurmak**: Helm 3 namespace-scoped release tutar, **ama** cluster-wide kaynak (CRD, ClusterRole) üretirseniz çakışır. Adlandırmaya `{{ .Release.Namespace }}` prefix.
- **`helm install` ardından `kubectl edit`**: drift. Sonraki upgrade'de Helm değişikliği geri alır. Lumix kuralı: cluster sadece **Git'ten ArgoCD ile** değişir.
- **CRD'yi `templates/` içine koymak**: upgrade'de güncellenmiyor → eski sürüm kalır. `crds/` klasörüne koyun veya ayrı bootstrap chart yapın.
- **`lookup` ile cluster state okuyarak template**: `helm template` (off-cluster render) sırasında bu fonksiyon **null** döner, sonuç farklı YAML. ArgoCD ile dry-run çakışır. Mümkünse kaçının.
- **Secret'ı `values.yaml` içine yazmak**: Git geçmişine sızar. ExternalSecret + Vault zorunlu.
- **`values.yaml` üzerinde `--reset-values`**: yanlış kullanımda önceki override'lar uçar. `--reuse-values` veya tüm değerleri explicit `-f` ile geç.
- **Sub-chart override sözdizimi yanlış**: umbrella values'ta sub-chart için `<chartName>:` prefix kullanılmalı. Yanlış prefix sessizce ignore edilir.
- **Version bump yapmadan değişiklik**: ArgoCD aynı versiyonu cache'leyebilir. Helm version'ı **her değişiklikte** artır (SemVer disiplin).
- **`maxUnavailable: %25` + ölü probe**: rolling update sırasında trafik düşer. Lumix: `maxUnavailable: 0`, `maxSurge: 1` (her seferinde 1 ekstra, hiç düşürmeden döndür).
- **Hook delete-policy unutmak**: pre-upgrade Job'ları birikir, namespace çöp dolar. `hook-delete-policy: before-hook-creation,hook-succeeded` ekleyin.
- **`helm rollback` yerine `kubectl rollout undo`**: K8s sadece Deployment'a döner; Helm release state'i değişmez → bir sonraki Helm upgrade tekrar son state'i deploy eder.
- **`values.yaml` çok şişman**: Lumix kuralı: chart default'u **minimal**; tier overlay'leri ayrı dosyada.

## 8. Diğer konularla ilişkisi

- [Kubernetes Temelleri](./01-kubernetes-fundamentals.md) — chart hangi nesneleri üretiyor
- [K3s](./02-k3s-lightweight-k8s.md) — Helm'in çalıştığı cluster
- [Helm Versioning](../21-ci-cd/05-helm-versioning.md) — chart vs app version, promotion
- [ArgoCD GitOps](../21-ci-cd/04-argocd-gitops.md) — Helm chart'ı GitOps ile uygulamak
- [GitLab CI Pipelines](../21-ci-cd/02-gitlab-ci-pipelines.md) — chart paketleme job'u
- [cert-manager TLS](./08-cert-manager-tls.md) — Certificate kaynaklarını chart içinde değil ayrı yönetmek
- [External Secrets / Vault](../security-compliance) — secret'ı chart'a koymamak

## 9. Daha derine inmek için

- Resmi doc: [https://helm.sh/docs/](https://helm.sh/docs/)
- Best practices: [https://helm.sh/docs/chart_best_practices/](https://helm.sh/docs/chart_best_practices/)
- Bitnami chart'larından örnek pattern'ler (mature reference).
- **Learn Helm** — Andrew Block, Austin Dewey
- Search keyword'leri: *"helm umbrella chart pattern"*, *"helm hook ordering"*, *"helm values schema validation"*, *"helm oci registry"*
- Lumix engineering-notes referansı: `21-kubernetes-helm-infra-devops-engineering-guide.md`

## 10. Sözlük

- **Helm**: K8s için paket yöneticisi.
- **Chart**: K8s manifest template'lerinin paketlenmiş hali.
- **Release**: Bir chart'ın belirli `values` ile cluster'da kurulu canlı kopyası.
- **Repository**: Chart'ların yayınlandığı HTTPS veya OCI tabanlı dağıtım kanalı.
- **`Chart.yaml`**: chart metadata dosyası.
- **`values.yaml`**: chart'ın varsayılan parametre dosyası.
- **Template**: Go template syntax ile yazılmış manifest taslağı.
- **`_helpers.tpl`**: macro/include tanımlarının yapıldığı template dosyası.
- **Hook**: Release lifecycle'ı içinde özel zamanlarda çalıştırılan Job/Pod (pre-install, post-upgrade vs).
- **Subchart**: `dependencies:` ile başka chart'a bağımlılık olarak eklenen chart.
- **Umbrella chart**: Yalnızca alt-chart'ları bir araya getiren üst-chart.
- **`appVersion` vs `version`**: uygulamanın versiyon string'i vs chart'ın paket versiyonu (SemVer).
- **`helm rollback`**: Bir önceki revision'a dönüş.
- **`helm template`**: Render edilmiş YAML'ı dosyaya/stdout'a yazmak (cluster'a dokunmadan).
