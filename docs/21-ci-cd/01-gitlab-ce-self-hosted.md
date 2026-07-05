---
title: GitLab CE Self-Hosted
description: GitLab CE self-host kurulumu — repo + CI + container registry tek araç. Projects/groups, runner registration, backup.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Lumix kod barındırma + CI + container registry + Helm OCI registry için **tek araç** olarak **GitLab Community Edition (CE) self-hosted** kullanır. Bu sayfa GitLab CE'yi sıfırdan anlatır, **EE ile farkını** netleştirir, **kurulum (Omnibus / Helm)**, **proje + grup yapısı**, **GitLab Runner kayıt akışı**, **container registry kullanımı**, **backup/restore** ve **GitLab kendi kendisinin upgrade**'i konularını detaylandırır. Hedef kitle: Git temellerini bilen, ekipte CI/CD'yi sıfırdan kuracak DevOps.

## 1. Bu nedir? (Sıfırdan)

**GitLab**, end-to-end DevOps platformu. Bileşenler:
- **Git repository management** (issues, MR, code review)
- **CI/CD** (`.gitlab-ci.yml` pipeline)
- **Container Registry** (OCI image)
- **Package Registry** (npm, Maven, Helm, Terraform module)
- **Pages**, **Wiki**, **Snippets**
- **Security tools** (SAST, DAST, dependency scan — kısmen EE)
- **Issue tracker / Kanban / Milestones**

İki sürüm:
- **CE (Community Edition)**: MIT lisans, ücretsiz, self-host. Çoğu özellik.
- **EE (Enterprise Edition)**: Bazı özellikler (multi-LDAP, advanced security, audit) için ticari lisans.

Lumix kararı: **CE self-hosted**. Sebepleri detaylı bölüm 5'te.

### Günlük hayattan analoji

Şirket içi ortak ofis: kod kutusu (repo), toplantı odası (MR review), depo (registry), üretim hattı (CI). Tek bir bina, tek bir bina yönetimi. Alternatif: ayrı GitHub + ayrı Jenkins + ayrı Nexus + ayrı Jira → 4 farklı vendor, 4 farklı login, 4 farklı bakım.

## 2. Hangi problemi çözüyor?

| Acı | Tek araç (GitLab) yok | GitLab var |
|---|---|---|
| Geliştirici farklı UI'larda gezer | GitHub + Jenkins + Nexus + Jira | Tek UI |
| Auth tekrarı | 4 farklı SSO config | Tek Keycloak entegrasyonu |
| Pipeline trigger | Webhook karmaşıklığı | Native push trigger |
| Image registry ayrı | Manuel docker tag + push | Built-in registry |
| Audit korelasyon | 4 farklı log | Tek audit |
| Lisans yönetimi | 4 farklı vendor | Tek (CE = ücretsiz) |
| KVKK / on-prem | Bulut bağımlılığı | Self-host |

### Patlamış üretim hikayesi

Bir takım GitHub.com + CircleCI + Docker Hub + Jira kullanıyordu. KVKK denetimi: "Türkiye dışında veri saklıyor musun?" Her aracın yanıtı evet. Migration projesi büyük. Self-host tek araç olsaydı: tek noktada KVKK uyumu. Lumix baştan self-host CE.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Mimari (GitLab Omnibus)

GitLab tek paket içinde:
- **Workhorse** (reverse proxy + uploads)
- **Sidekiq** (background job)
- **Gitaly** (Git storage)
- **Puma** (Rails app server)
- **PostgreSQL** (DB)
- **Redis** (cache, sidekiq queue)
- **Container Registry** (Docker distribution)
- **Nginx** (reverse proxy)
- **Mailroom** (email replies)

Lumix kararı: **Helm chart** kullanır (K8s'te), Omnibus değil. K8s yönetimi diğer şeylerle ortak.

### 3.2. GitLab Helm chart

```bash
helm repo add gitlab https://charts.gitlab.io/
helm install gitlab gitlab/gitlab \
  --namespace gitlab \
  --create-namespace \
  --version 8.0.0 \
  -f values-gitlab.yaml
```

Chart bileşenleri:
- `gitlab/webservice` (Puma)
- `gitlab/sidekiq`
- `gitlab/gitaly` (StatefulSet)
- `gitlab/postgresql` (veya external)
- `gitlab/redis`
- `registry/` (container registry)
- `nginx-ingress`
- `cert-manager` (chart içi otomatik)
- `minio` (object storage — Lumix RustFS kullanır)
- `runner/` (opsiyonel; GitLab Runner)

### 3.3. Group ve Project hiyerarşisi

```
gitlab.lumix.io/
├── platform/                  (üst seviye group)
│   ├── microservices/         (subgroup)
│   │   ├── identity-service
│   │   ├── academic-service
│   │   └── ...
│   ├── charts/                (Helm chart repository)
│   │   ├── identity-service
│   │   ├── academic-service
│   │   └── lumix-platform
│   ├── infrastructure/
│   │   ├── terraform-modules
│   │   ├── ansible-playbooks
│   │   └── argocd-apps
│   └── tools/
│       ├── license-generator
│       └── customer-provisioning
├── customers/                 (her müşteri için ayrı subgroup)
│   ├── omer-okullari/
│   │   └── ... (Terraform state, customer profile)
│   └── x-vakfi/
└── internal/                  (Lumix iç araçlar)
    └── admin-panel
```

### 3.4. Runner

GitLab Runner: pipeline job'larını çalıştıran agent.

Lumix tercihi: **Kubernetes Executor** (Helm). Runner pod açar, içinde job çalışır, biter.

```bash
helm install gitlab-runner gitlab/gitlab-runner \
  --namespace gitlab-runners \
  -f values-runner.yaml
```

```yaml
gitlabUrl: https://gitlab.lumix.io
runnerRegistrationToken: ${RUNNER_TOKEN}  # ESO ile Vault'tan

runners:
  privileged: false
  serviceAccountName: gitlab-runner
  config: |
    [[runners]]
      executor = "kubernetes"
      [runners.kubernetes]
        namespace = "{{.Release.Namespace}}"
        image = "alpine:3.20"
        cpu_request = "100m"
        memory_request = "256Mi"
        cpu_limit = "2"
        memory_limit = "4Gi"
        privileged = false
        helper_image = "gitlab/gitlab-runner-helper:x86_64-latest"
```

### 3.5. Container Registry

GitLab Container Registry: OCI compliant, project-level.

```
URL pattern: registry.lumix.io/<group>/<project>/<image>:<tag>
Örnek: registry.lumix.io/platform/microservices/academic-service:1.4.2
```

Kullanım:
```bash
docker login registry.lumix.io -u $CI_REGISTRY_USER -p $CI_REGISTRY_PASSWORD
docker push registry.lumix.io/platform/microservices/academic-service:1.4.2
helm push academic-service-0.4.1.tgz oci://registry.lumix.io/platform/charts
```

### 3.6. SSO entegrasyonu

GitLab → Keycloak OIDC:

```yaml
# values-gitlab.yaml parça
global:
  appConfig:
    omniauth:
      enabled: true
      providers:
        - secret: gitlab-keycloak-config
          key: provider
```

Secret:
```yaml
provider: |
  name: 'openid_connect'
  label: 'Lumix SSO'
  args:
    name: 'openid_connect'
    scope: ['openid', 'profile', 'email']
    response_type: code
    issuer: 'https://keycloak.lumix.io/realms/lumix-staff'
    client_auth_method: 'query'
    discovery: true
    uid_field: 'preferred_username'
    client_options:
      identifier: 'gitlab'
      secret: '${{KEYCLOAK_GITLAB_SECRET}}'
      redirect_uri: 'https://gitlab.lumix.io/users/auth/openid_connect/callback'
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Yer ve topoloji

Lumix-internal cluster'da (Rancher Local cluster). Tek müşteri cluster'larına dağıtılmaz.

```
gitlab.lumix.io                  # Web UI + Git HTTPS
ssh.gitlab.lumix.io:22           # Git SSH
registry.lumix.io                # Container + Helm OCI registry
```

### 4.2. Versiyon

GitLab 17.x (LTS-benzeri major). Upgrade yılda 1-2 major; her major release notes incelenir.

### 4.3. PostgreSQL ve Redis

GitLab chart **kendi PostgreSQL** ve **Redis**'i ile gelir. Lumix kararı: **chart-içi varsayılan** kullanır (ayrı operasyonel yük doğurmamak için). Backup PostgreSQL native + Velero.

### 4.4. Object storage

GitLab artifacts, LFS, registry layer'ları için object storage:
- `lumix-gitlab-artifacts`
- `lumix-gitlab-lfs`
- `lumix-gitlab-registry`
- `lumix-gitlab-backup`

RustFS S3-compatible endpoint kullanılır.

### 4.5. Runner topolojisi

İki runner cluster:
- **`gitlab-runners-internal`** (Lumix-internal cluster): pipeline çoğunluğu burada (build, test, scan, helm package).
- **`gitlab-runners-deploy`** (Lumix-internal cluster): production deploy job'ları, daha sıkı RBAC.

Runner registration token Vault'ta.

### 4.6. RBAC modeli

| Rol (GitLab) | Kapsam |
|---|---|
| Owner | Lumix platform admin |
| Maintainer | Servis lead'leri, MR merge yetkisi |
| Developer | Geliştiriciler, push to feature branch, MR create |
| Reporter | Read-only |
| Guest | İçerik görme (issue, wiki) |

Keycloak grupları → GitLab group membership otomatik mapping (via SCIM yok ama OIDC claim'leri).

### 4.7. Project template

Lumix yeni microservice için bir **project template** tutar:
- `.gitlab-ci.yml` boilerplate
- Helm chart skeleton
- Dockerfile + multi-stage
- Pre-commit hook'ları
- `CODEOWNERS` örnek
- `MR template`

`gitlab.lumix.io/platform/templates/microservice-template` → "Create new project from template".

### 4.8. Branch protection

`main` branch:
- Force push yasak
- Direct push yasak (sadece MR ile)
- MR approval count: 2
- Pipeline must succeed
- CODEOWNERS approval required

Feature branches: `feature/JIRA-123-attendance-mark`. Sözleşme: Jira ID prefix zorunlu (CI lint).

### 4.9. CI/CD variables

Project-level:
- `KUBECONFIG_PROD` (CI file variable, base64)
- `REGISTRY_USER` / `REGISTRY_TOKEN`
- `VAULT_ROLE_ID` / `VAULT_SECRET_ID` (AppRole auth)

Group-level (shared):
- `ARGOCD_URL`
- `RANCHER_URL`
- Common variables ile group inheritance

### 4.10. Backup

GitLab Helm chart `task-runner` pod ile backup:

```bash
# Full backup (CronJob)
kubectl exec -n gitlab gitlab-toolbox-xxx -- \
  backup-utility --backend rustfs --location lumix-gitlab-backup

# Restore (test cluster'da)
kubectl exec -n gitlab gitlab-toolbox-xxx -- \
  backup-utility --restore <backup-id>
```

Velero ek olarak K8s state ve PV backup'ı yapar.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **GitHub.com** | Bulut, KVKK/data residency endişesi. |
| **GitHub Enterprise Server (self-host)** | Ticari lisans pahalı. |
| **Gitea** | Hafif, harika; ama CI/container registry/issue ekosistemi GitLab kadar olgun değil. |
| **Bitbucket Data Center** | Atlassian ekosistem; ticari. |
| **Gerrit** | Code review odaklı, CI/registry yok. |
| **Forgejo** | Gitea fork; topluluk küçük. |
| **GitHub.com + Drone CI + Harbor + Jira** | 4 farklı vendor; entegrasyon yükü. |

### Kabul ettiğimiz trade-off'lar

- **GitLab kaynak yoğun**: Ruby + sidekiq + PostgreSQL + Redis + Gitaly = ~8-12 GB RAM. Karşılığında: tek noktada her şey.
- **EE özellikleri yok**: Compliance pipeline, advanced security scanning, push rules — bazı işleri manuel yaparız.
- **GitLab upgrade büyük**: yılda 2 major, her major migration. Lumix kuralı: staging cluster'da test sonra prod.
- **Self-host operasyonu**: bizim cluster'da. Patch, backup, monitor sorumluluğumuz.

### Tekrar değerlendirme tetikleyicileri

- 100+ aktif geliştirici olursa EE özellikleri (push rules, code owners stricter) anlamlı olabilir.
- Compliance regülasyonu Code Owners + Audit zorunlu kılarsa EE.

## 6. Pratik örnek

### 6.1. Helm values özet

```yaml
# values-gitlab.yaml
global:
  hosts:
    domain: lumix.io
    gitlab:
      name: gitlab.lumix.io
    registry:
      name: registry.lumix.io
  ingress:
    configureCertmanager: false
    tls:
      secretName: gitlab-tls
  edition: ce
  appConfig:
    object_store:
      enabled: true
      connection:
        secret: gitlab-object-storage
    omniauth:
      enabled: true
      autoSignInWithProvider: openid_connect
      blockAutoCreatedUsers: false
      providers:
        - secret: gitlab-keycloak-config
          key: provider
  initialRootPassword:
    secret: gitlab-initial-root-password
    key: password

postgresql:
  install: true
  fullnameOverride: gitlab-postgresql

redis:
  install: true

gitlab:
  webservice:
    minReplicas: 2
    resources:
      requests:
        cpu: 500m
        memory: 2Gi
      limits:
        cpu: 2000m
        memory: 4Gi
  sidekiq:
    minReplicas: 2
  toolbox:
    backups:
      cron:
        enabled: true
        schedule: "0 1 * * *"
        persistence:
          enabled: false
        objectStorage:
          backend: s3
          config:
            secret: gitlab-object-storage

registry:
  enabled: true
  storage:
    secret: gitlab-registry-storage
    key: config
  hpa:
    minReplicas: 2

nginx-ingress:
  enabled: false   # Lumix'in kendi Traefik'i kullanılır
```

### 6.2. Lumix Traefik IngressRoute

```yaml
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: gitlab
  namespace: lumix-system
spec:
  entryPoints: [websecure]
  routes:
    - match: Host(`gitlab.lumix.io`)
      kind: Rule
      services:
        - name: gitlab-webservice-default
          namespace: gitlab
          port: 8080
    - match: Host(`registry.lumix.io`)
      kind: Rule
      services:
        - name: gitlab-registry
          namespace: gitlab
          port: 5000
  tls:
    secretName: lumix-io-wildcard
```

### 6.3. Runner Helm install

```bash
helm install gitlab-runner gitlab/gitlab-runner \
  --namespace gitlab-runners-internal \
  --create-namespace \
  -f values-runner-internal.yaml
```

`values-runner-internal.yaml`:
```yaml
gitlabUrl: https://gitlab.lumix.io
runnerRegistrationToken: ${{LOOKUP_FROM_VAULT}}

replicas: 3
concurrent: 30
checkInterval: 30

rbac:
  create: true

runners:
  config: |
    [[runners]]
      executor = "kubernetes"
      environment = ["FF_USE_FASTZIP=true"]
      [runners.kubernetes]
        namespace = "{{.Release.Namespace}}"
        image = "alpine:3.20"
        privileged = false
        pull_policy = "if-not-present"
        helper_image = "gitlab/gitlab-runner-helper:x86_64-latest"
        cpu_request = "100m"
        memory_request = "256Mi"
        cpu_limit = "4"
        memory_limit = "8Gi"
        service_account = "gitlab-runner"
        [[runners.kubernetes.volumes.empty_dir]]
          name = "docker-cache"
          mount_path = "/cache"
          medium = "Memory"
```

### 6.4. Yeni project oluşturma (API ile)

```bash
curl -X POST https://gitlab.lumix.io/api/v4/projects \
  -H "PRIVATE-TOKEN: $GITLAB_ADMIN_TOKEN" \
  -d "name=new-service" \
  -d "namespace_id=42" \
  -d "template_name=lumix-microservice-template" \
  -d "visibility=private"
```

### 6.5. SSH key + .gitconfig

```bash
# Geliştirici makinesinde
ssh-keygen -t ed25519 -C "ahmet@lumix.io"
cat ~/.ssh/id_ed25519.pub
# → GitLab Profile → SSH Keys → Add

git config --global user.email "ahmet@lumix.io"
git config --global user.name "Ahmet Y."
git config --global pull.rebase true
git config --global init.defaultBranch main

# Clone
git clone git@ssh.gitlab.lumix.io:platform/microservices/academic-service.git
```

### 6.6. Backup verify drill

```bash
# Aylık restore drill (staging cluster)
kubectl exec -n gitlab-staging gitlab-toolbox-xxx -- \
  backup-utility --restore <prod-backup-id> --skip db,registry

# Login + sample MR var mı?
curl -fs https://gitlab-staging.lumix.io/-/health
```

## 7. Dikkat edilecek tuzaklar

- **`initialRootPassword` Secret'ını saklamamak**: ilk login için lazım; sonra rotate edilmeli. Vault.
- **Backup'ın test edilmemesi**: bir gün lazım olunca restore prosedürü bilinmiyor. Aylık drill.
- **GitLab upgrade'ini doğrudan production'a uygulamak**: chart upgrade migration job'u çalışır; eğer fail ederse pod'lar boot olmaz. Staging cluster'da test zorunlu.
- **Container Registry storage'ını object store'a almamak**: PV dolması = registry down. Object storage (RustFS) zorunlu.
- **Runner privilege**: privileged: true gerektiren Docker-in-Docker yerine **buildkit / kaniko / podman** rootless. Privilege escalation riski.
- **Runner concurrent çok yüksek**: cluster CPU/RAM patlar. Lumix limit: 30 concurrent per runner replica.
- **GitLab tek instance**: pod ölünce 5-10 dk downtime. Webservice replica ≥ 2.
- **Sidekiq queue dolması**: arka plan işleri (notification, mirror) birikir. Monitoring + alert.
- **Gitaly state**: tek replica StatefulSet. PV backup şart.
- **Registry retention policy**: image'lar birikir, disk dolar. Project-level retention: keep last 10 tags, untagged 7 gün sonra sil.
- **OIDC redirect URI yanlış**: SSO callback fail. Keycloak client redirect_uri tam URL.
- **`CI_JOB_TOKEN` ile push to registry**: standart akış; PAT yerine job token kullanılınca refresh sorunu yok.
- **Self-host Cron backup runner her gece tüm db**: büyük instance'da 1 saat sürer. Incremental backup yoluyla optimize.

## 8. Diğer konularla ilişkisi

- [GitLab CI Pipelines](./gitlab-ci-pipelines) — `.gitlab-ci.yml` yazımı
- [Trivy Image Scanning](./trivy-image-scanning) — CI içinde scan
- [ArgoCD GitOps](./argocd-gitops) — Git repo source of truth
- [Helm Versioning](./helm-versioning) — Helm chart registry GitLab'ta
- [Customer Onboarding Pipeline](../20-iac-provisioning/customer-onboarding-pipeline) — GitLab CI ile orkestrasyon
- [Authentication](../04-authentication-authorization) — Keycloak SSO
- [Velero Backup](../infra-devops/velero-backup) — GitLab cluster backup

## 9. Daha derine inmek için

- Resmi doc: [https://docs.gitlab.com/](https://docs.gitlab.com/)
- GitLab Architecture: [https://docs.gitlab.com/ee/development/architecture.html](https://docs.gitlab.com/ee/development/architecture.html)
- GitLab Helm chart: [https://docs.gitlab.com/charts/](https://docs.gitlab.com/charts/)
- "GitLab Cookbook"
- Search keyword'leri: *"gitlab helm chart object storage"*, *"gitlab runner kubernetes executor"*, *"gitlab oidc keycloak"*, *"gitlab backup restore omnibus"*

## 10. Sözlük

- **GitLab CE / EE**: Community Edition (MIT, free) / Enterprise Edition (ticari).
- **Omnibus**: Tek paket distribution (apt/deb).
- **GitLab Runner**: CI job çalıştıran agent.
- **Executor**: Runner'ın job'ı nerede çalıştırdığı (shell, docker, kubernetes, ssh).
- **Gitaly**: GitLab'ın Git repo storage backend'i.
- **Workhorse**: GitLab'ın HTTP reverse proxy + büyük dosya upload kanalı.
- **Sidekiq**: Background job processor (Ruby).
- **Container Registry**: Project-scoped OCI image storage.
- **Package Registry**: npm/Maven/Helm/Terraform package storage.
- **Project / Group / Subgroup**: GitLab hiyerarşi.
- **CI/CD variables (Project / Group / Instance)**: Pipeline secret'ları farklı scope'larda.
- **CI_JOB_TOKEN**: CI job sırasında oluşturulan, registry/api erişimi için token.
- **Branch protection**: Belirli branch'lere doğrudan push'u engelleyen policy.
- **Code Owners**: Belirli path'leri sahiplenen kişiler; review zorunlu.
- **Maintenance mode**: GitLab upgrade sırasında read-only mod.
