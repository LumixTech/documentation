---
title: Customer Onboarding Pipeline
description: Tam akış — müşteri satışı → Terraform VPS → Ansible OS+K3s+Rancher agent → ArgoCD app deploy → Ansible customer seed → ready. 4 katmanlı pipeline, GitLab CI orchestration.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Yeni bir müşteri "Ömer Okulları" Lumix satın aldığında: VPS sipariş, OS kurulum, K3s cluster, Rancher import, application deploy, müşteri seed (Keycloak realm, Vault path, Kafka topic, ilk admin user), lisans aktivasyonu — hepsi tek pipeline. Bu sayfa **4 katmanlı orkestrasyon**'u (Terraform → Ansible OS → ArgoCD App → Ansible Seed) detaylandırır, GitLab CI pipeline yapısını gösterir, **idempotent** + **resumable** + **observable** olma şartlarını açıklar, hata senaryolarını ve **rollback** stratejisini tarif eder. Hedef kitle: Terraform ve Ansible temellerini bilen ([Terraform Basics](./01-terraform-basics.md), [Ansible Basics](./02-ansible-basics.md)) ekip lideri / DevOps. Bu sayfa tüm IaC + CI/CD bileşenlerinin uçtan uca buluştuğu sayfadır.

## 1. Bu nedir? (Sıfırdan)

**Customer onboarding pipeline**, Lumix sağlayıcısının yeni bir müşteri için **otomatize bir akışla** tam bir kurulum yapma süreci. Hedef:

> Sales onayı → ~2 saat içinde müşteri kurulumu hazır, müşteri login olabilir.

Bu zaman içerisinde:
1. VPS sağlayıcısına sipariş
2. OS hardening + K3s install
3. Cluster Rancher'a import
4. cert-manager, Velero, External Secrets, monitoring stack
5. Lumix uygulama yığını ArgoCD ile deploy
6. Müşteri seed (auth realm, topics, ilk tenant, ilk admin user)
7. Lisans aktivasyonu
8. Smoke test
9. Müşteriye URL + ilk login bilgisi gönderimi

### Günlük hayattan analoji

Yeni mağaza açılışı: bina kira (VPS), elektrik+su (OS+network), rafları kur (K8s+app), ürünleri yerleştir (seed), girişe levha as (DNS), mağaza müdürünü ata (admin user), kapıyı aç. Tek bir checklist; her adım sırayla; bir adım atlanırsa sonraki düzgün başlamaz.

## 2. Hangi problemi çözüyor?

Otomasyonsuz onboarding:
- Manuel adımlar, gün(ler) süre
- Müşteri farklı kurulumlar, tutarsızlık
- Atlanan adımlar (örn. lisans aktivasyonu, audit user oluşturma)
- Hata sonrası nereden devam edileceği belirsiz
- Sales-engineering eşleşmesi: her satıştan sonra mühendis tetiklenir

| Acı | Otomasyonsuz | Pipeline'lı |
|---|---|---|
| Onboarding süresi | 1-3 gün | 2 saat |
| Tutarlılık | Müşteri başına farklı | Aynı template |
| Hata kurtarma | Manuel debug + kalan adımlar | Resumable pipeline + step status |
| Audit | Email/Slack dağınık | GitLab CI log + Vault audit |
| Müşteri başına maliyet | Mühendis × N saat | Pipeline + smoke test review |
| Ölçeklenebilirlik | 5 müşteri/ay | 100+ müşteri/ay |

### Patlamış üretim hikayesi

Bir SaaS firması her müşteri için 3 kişilik bir ekip 1 gün geçiriyordu. Yıl sonunda satış %200 arttı; ops ekibi yetişmedi. Pipeline yatırımıyla onboarding 90 dakikaya indi, ekip aynı kaldı, satış skalalandı. Lumix bunu en başından planlıyor.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. 4 katmanlı mimari

```
┌────────────────────────────────────────────────────────────────────┐
│  LAYER 1: INFRA (Terraform)                                        │
│   • VPS provisioning (Hetzner/AWS/DO)                              │
│   • Private network, firewall                                      │
│   • Cloudflare DNS (api.*, admin.*, k8s-api.*)                     │
│   Output: IP listesi, ansible inventory                            │
└─────────────────────────────┬──────────────────────────────────────┘
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│  LAYER 2: OS + K8S (Ansible)                                       │
│   • Bootstrap user (lumix-admin)                                   │
│   • Ubuntu hardening (UFW, fail2ban, auditd, AppArmor)            │
│   • K3s install (HA topology)                                      │
│   • Rancher Manager'a cluster import                               │
│   • Cluster addons: cert-manager, ESO, Calico, Velero, Promtail    │
│   Output: ready cluster + kubeconfig + Rancher cluster ID          │
└─────────────────────────────┬──────────────────────────────────────┘
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│  LAYER 3: APP (ArgoCD)                                             │
│   • ArgoCD Application oluştur (lumix-platform umbrella chart)     │
│   • Sync: tüm microservice'ler, Kafka, PostgreSQL, Redis, Kong,    │
│           Traefik, Temporal, Elasticsearch, RustFS                 │
│   Output: tüm pod'lar Running + Healthy                            │
└─────────────────────────────┬──────────────────────────────────────┘
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│  LAYER 4: SEED (Ansible)                                           │
│   • Vault customer path init + secret seed                         │
│   • Keycloak realm (opsiyonel)                                     │
│   • Kafka topic create (her servisin topic'i)                      │
│   • PostgreSQL ilk tenant + RLS aktivasyonu                        │
│   • Identity-service: ilk admin user + permission                  │
│   • License activation (license-service)                           │
│   • Smoke test (auth, basic endpoint)                              │
│   • Notification: müşteriye e-posta                                │
└────────────────────────────────────────────────────────────────────┘
```

### 3.2. Idempotency garantisi

Her katman idempotent:
- Terraform: state'e bakar; resource varsa create etmez.
- Ansible: module'ler idempotent (apt, file, user).
- ArgoCD: declarative; aynı manifest tekrar apply = no-op.
- Seed: API check + create if missing (`vault kv get` → eksikse write).

Pipeline yarıda kalırsa, başa dönüp `re-run` güvenli.

### 3.3. Resumable (yarıda kalmaya dayanıklı)

Her layer'ın sonunda **state marker** Vault'a yazılır:

```
secret/lumix/onboarding/<customer-id>/status:
  layer1_infra: completed_at=2026-05-27T14:32:00Z
  layer2_os: completed_at=2026-05-27T14:55:00Z
  layer3_app: completed_at=2026-05-27T15:20:00Z
  layer4_seed: completed_at=2026-05-27T15:40:00Z
  overall: ready
```

Pipeline her başlangıçta bu marker'ı okur; tamamlanmış layer'ları atlar veya verify-only çalıştırır.

### 3.4. Observable

Her layer GitLab CI job'unda log'lanır + Loki'ye gönderilir. Slack alert layer fail'inde. Müşteri dashboard'unda real-time status (eğer müşteri self-service portal kullanıyorsa).

### 3.5. Akış diagramı

```
            ┌──────────────────────┐
            │  Sales onayı + form  │
            │  (customer info)     │
            └──────────┬───────────┘
                       ▼
            ┌──────────────────────┐
            │  GitLab MR oluştur   │
            │  customers/<id>/ ekle│
            └──────────┬───────────┘
                       ▼  (manuel approve)
            ┌──────────────────────┐
            │  Pipeline tetiklenir │
            └──────────┬───────────┘
                       ▼
        ┌──── Layer 1: Terraform ────┐
        │   plan → review → apply    │
        └────────────┬───────────────┘
                     ▼
        ┌──── Layer 2: Ansible OS ───┐
        │   bootstrap → harden →     │
        │   k3s-install → rancher    │
        └────────────┬───────────────┘
                     ▼
        ┌──── Layer 3: ArgoCD App ───┐
        │   app create → sync wait   │
        └────────────┬───────────────┘
                     ▼
        ┌──── Layer 4: Ansible Seed ─┐
        │   vault → keycloak →       │
        │   kafka → postgres →       │
        │   identity (admin user) →  │
        │   license → smoke          │
        └────────────┬───────────────┘
                     ▼
            ┌──────────────────────┐
            │  Müşteriye bildirim  │
            └──────────────────────┘
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Repository yapısı

```
gitlab.lumix.io/platform/customer-provisioning/
├── terraform/
│   └── customers/{customer-id}/...
├── ansible/
│   ├── inventories/{customer-id}/...
│   └── playbooks/01-...05-...
├── argocd-apps/
│   └── customers/{customer-id}/lumix-platform.yaml
├── seed/
│   └── customers/{customer-id}/values.yaml
└── .gitlab-ci.yml
```

Yeni müşteri = yeni klasör + MR. CI tetiklenir.

### 4.2. Customer profile (MR'da gelir)

```yaml
# customers/omer-okullari/profile.yaml
customer_id: omer-okullari
display_name: Ömer Okulları A.Ş.
tier: m
region: tr-istanbul
provider: hetzner
hcloud_location: fsn1
license:
  tenants_max: 5
  modules: [identity, organization, academic, finance, file]
  valid_until: "2027-04-30"
contacts:
  primary_admin:
    email: yonetici@omerokullari.k12.tr
    name: Ahmet Yılmaz
  technical:
    email: it@omerokullari.k12.tr
data_residency: tr
gdpr_required: false   # Türkiye müşterisi; KVKK var
keycloak_enabled: false
```

### 4.3. GitLab CI pipeline

```yaml
# .gitlab-ci.yml
stages:
  - validate
  - infra
  - os
  - app
  - seed
  - smoke
  - notify

variables:
  CUSTOMER_ID: ${CUSTOMER_ID}    # MR'dan veya pipeline tetikleme sırasında set edilir
  VAULT_ADDR: https://vault.lumix.io

before_script:
  - export VAULT_TOKEN=$(vault write -field=token auth/jwt/login role=gitlab-ci jwt=$CI_JOB_JWT)

# ─── VALIDATE ────────────────────────────────────────
validate-customer-profile:
  stage: validate
  image: alpine:3.20
  script:
    - apk add --no-cache yq
    - yq eval '.customer_id, .tier, .region, .provider' customers/${CUSTOMER_ID}/profile.yaml
    - test -f customers/${CUSTOMER_ID}/profile.yaml

# ─── LAYER 1: INFRA ───────────────────────────────────
terraform-apply:
  stage: infra
  image: hashicorp/terraform:1.9
  script:
    - cd terraform/customers/${CUSTOMER_ID}
    - terraform init -backend-config="address=${TF_BACKEND_URL}/${CUSTOMER_ID}"
    - terraform plan -out=plan.cache
    - terraform apply -input=false plan.cache
    - terraform output -raw ansible_inventory > ../../../ansible/inventories/${CUSTOMER_ID}/hosts.ini
  artifacts:
    paths:
      - ansible/inventories/${CUSTOMER_ID}/hosts.ini
    expire_in: 1 day

mark-layer1-done:
  stage: infra
  needs: [terraform-apply]
  script:
    - vault kv patch secret/lumix/onboarding/${CUSTOMER_ID}/status layer1_infra="completed_at=$(date -Is)"

# ─── LAYER 2: OS + K8S ────────────────────────────────
.ansible-template: &ansible-template
  image: registry.lumix.io/lumix/ansible-runner:2.16
  before_script:
    - export VAULT_TOKEN=$(vault write -field=token auth/jwt/login role=gitlab-ci jwt=$CI_JOB_JWT)
    - mkdir -p ~/.ssh
    - vault kv get -field=private_key secret/lumix/ssh-keys/ansible-bootstrap > ~/.ssh/id_ed25519
    - chmod 600 ~/.ssh/id_ed25519

ansible-bootstrap:
  <<: *ansible-template
  stage: os
  needs: [terraform-apply]
  script:
    - cd ansible
    - ansible-playbook -i inventories/${CUSTOMER_ID}/hosts.ini playbooks/00-bootstrap.yml

ansible-harden:
  <<: *ansible-template
  stage: os
  needs: [ansible-bootstrap]
  script:
    - cd ansible
    - ansible-playbook -i inventories/${CUSTOMER_ID}/hosts.ini playbooks/01-hardening.yml

ansible-k3s-install:
  <<: *ansible-template
  stage: os
  needs: [ansible-harden]
  script:
    - cd ansible
    - ansible-playbook -i inventories/${CUSTOMER_ID}/hosts.ini playbooks/02-k3s-install.yml

ansible-rancher-import:
  <<: *ansible-template
  stage: os
  needs: [ansible-k3s-install]
  script:
    - cd ansible
    - ansible-playbook -i inventories/${CUSTOMER_ID}/hosts.ini playbooks/03-rancher-import.yml

ansible-cluster-addons:
  <<: *ansible-template
  stage: os
  needs: [ansible-rancher-import]
  script:
    - cd ansible
    - ansible-playbook -i inventories/${CUSTOMER_ID}/hosts.ini playbooks/04-bootstrap-cluster-addons.yml

mark-layer2-done:
  stage: os
  needs: [ansible-cluster-addons]
  script:
    - vault kv patch secret/lumix/onboarding/${CUSTOMER_ID}/status layer2_os="completed_at=$(date -Is)"

# ─── LAYER 3: APP DEPLOY ──────────────────────────────
argocd-app-create:
  stage: app
  image: argoproj/argocd:v2.11
  needs: [ansible-cluster-addons]
  script:
    - export ARGOCD_AUTH_TOKEN=$(vault kv get -field=token secret/lumix/argocd/admin-token)
    - argocd login argocd.lumix.io --auth-token $ARGOCD_AUTH_TOKEN --grpc-web
    - envsubst < argocd-apps/customers/${CUSTOMER_ID}/lumix-platform.yaml | argocd app create -f -
    - argocd app sync lumix-platform-${CUSTOMER_ID} --timeout 1800 --prune

argocd-wait-healthy:
  stage: app
  image: argoproj/argocd:v2.11
  needs: [argocd-app-create]
  script:
    - argocd app wait lumix-platform-${CUSTOMER_ID} --health --timeout 1800

mark-layer3-done:
  stage: app
  needs: [argocd-wait-healthy]
  script:
    - vault kv patch secret/lumix/onboarding/${CUSTOMER_ID}/status layer3_app="completed_at=$(date -Is)"

# ─── LAYER 4: SEED ────────────────────────────────────
seed-vault:
  <<: *ansible-template
  stage: seed
  needs: [argocd-wait-healthy]
  script:
    - cd ansible
    - ansible-playbook -i inventories/${CUSTOMER_ID}/hosts.ini playbooks/05-customer-seed.yml --tags vault

seed-kafka:
  <<: *ansible-template
  stage: seed
  needs: [seed-vault]
  script:
    - cd ansible
    - ansible-playbook -i inventories/${CUSTOMER_ID}/hosts.ini playbooks/05-customer-seed.yml --tags kafka

seed-postgres-tenant:
  <<: *ansible-template
  stage: seed
  needs: [seed-vault]
  script:
    - cd ansible
    - ansible-playbook -i inventories/${CUSTOMER_ID}/hosts.ini playbooks/05-customer-seed.yml --tags postgres

seed-identity-admin:
  <<: *ansible-template
  stage: seed
  needs: [seed-postgres-tenant]
  script:
    - cd ansible
    - ansible-playbook -i inventories/${CUSTOMER_ID}/hosts.ini playbooks/05-customer-seed.yml --tags identity

seed-license:
  <<: *ansible-template
  stage: seed
  needs: [seed-identity-admin]
  script:
    - cd ansible
    - ansible-playbook -i inventories/${CUSTOMER_ID}/hosts.ini playbooks/05-customer-seed.yml --tags license

mark-layer4-done:
  stage: seed
  needs: [seed-license]
  script:
    - vault kv patch secret/lumix/onboarding/${CUSTOMER_ID}/status layer4_seed="completed_at=$(date -Is)"

# ─── SMOKE TEST ───────────────────────────────────────
smoke-test:
  stage: smoke
  image: curlimages/curl:8.8.0
  needs: [seed-license]
  script:
    - curl -fs https://api.${CUSTOMER_ID}.lumix.io/actuator/health
    - curl -fs https://admin.${CUSTOMER_ID}.lumix.io/health
    - |
      TOKEN=$(curl -s -X POST https://api.${CUSTOMER_ID}.lumix.io/api/v1/auth/login \
        -H "Content-Type: application/json" \
        -d '{"username":"admin@'${CUSTOMER_ID}'.lumix.io","password":"'${BOOTSTRAP_PASSWORD}'"}' | jq -r .accessToken)
    - test -n "$TOKEN"
    - curl -fs -H "Authorization: Bearer $TOKEN" https://api.${CUSTOMER_ID}.lumix.io/api/v1/admin/tenants

# ─── NOTIFY ───────────────────────────────────────────
notify-customer:
  stage: notify
  image: alpine:3.20
  needs: [smoke-test]
  script:
    - apk add --no-cache jq curl
    - |
      curl -X POST https://api.lumix.io/v1/notify/customer \
        -H "Content-Type: application/json" \
        -d @- <<EOF
      {
        "customer_id": "${CUSTOMER_ID}",
        "type": "onboarding_complete",
        "urls": {
          "admin": "https://admin.${CUSTOMER_ID}.lumix.io",
          "api": "https://api.${CUSTOMER_ID}.lumix.io"
        }
      }
      EOF
    - vault kv patch secret/lumix/onboarding/${CUSTOMER_ID}/status overall="ready"
```

### 4.4. Status marker'lardan akıllı yeniden çalıştırma

Her layer başında bir `check-already-done` job'u marker'ı kontrol eder. Tamamlanmışsa job skip. Bu sayede:
- Yarıda fail ederse pipeline retry → kaldığı yerden devam.
- Tamamlanmış pipeline tekrar tetiklendiğinde sadece smoke test.

### 4.5. Hata senaryoları + rollback

| Hata | Recovery |
|---|---|
| Terraform fail (provider quota) | Manuel quota yükselt → retry |
| VM SSH unreachable | Inventory IP doğrula, firewall kontrol, retry |
| K3s install timeout | Logları gözden geçir, gerekirse VM delete + retry from layer 1 |
| Rancher import fail | Rancher token yenile, retry |
| ArgoCD sync fail | Helm values hatası → fix MR → retry |
| Seed Vault fail | Vault token yenile, retry |
| License invalid | License generator yenile, retry |

**Full rollback**: `terraform destroy` (Layer 1) → tüm VPS silinir. Tüm onboarding state Vault'tan da silinir. Yeni baştan başlanır.

### 4.6. Manuel intervention gates

Production politikası:
- Layer 1 apply'dan önce **manuel approve** (cost sebebiyle).
- Smoke test fail olursa pipeline durur, ekip alert alır.
- Müşteriye bildirim **manuel onay** sonrası.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Monolit script (bash)** | Resumable değil, hata yönetimi zayıf. |
| **Jenkins pipeline** | GitLab ile entegre değil; ek araç. |
| **Ansible Tower / AWX** | Sadece Ansible orkestrasyon, Terraform + ArgoCD aralarında daha az entegre. |
| **Crossplane (K8s-native provisioning)** | K8s gerektirir, bootstrap için yumurta-tavuk. |
| **Atlantis (Terraform-only)** | Sadece TF; pipeline genel değil. |
| **Spinnaker** | Karmaşık, multi-cloud-app deploy odaklı, infra provisioning için aşırı. |

### Kabul ettiğimiz trade-off'lar

- **GitLab CI single point of failure**: GitLab down → onboarding duramaz. Lumix kararı: GitLab HA + backup.
- **Pipeline complexity**: 4 layer × birçok job. CI YAML uzun ama mantıksal.
- **Customer profile MR review zaman alır**: kalite kontrol; manuel onay aşaması bu yüzden var.

### Tekrar değerlendirme tetikleyicileri

- Müşteri sayısı 100+ olunca self-service portal gerekebilir (sales formundan tetik).
- Multi-region müşterileri için region selection logic genişlemeli.

## 6. Pratik örnek

### 6.1. ArgoCD Application manifest

```yaml
# argocd-apps/customers/omer-okullari/lumix-platform.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: lumix-platform-omer-okullari
  namespace: argocd
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: lumix-customers
  source:
    repoURL: oci://registry.lumix.io/charts
    chart: lumix-platform
    targetRevision: 2026.04.0
    helm:
      releaseName: lumix-platform
      valueFiles:
        - $values/customers/omer-okullari/values.yaml
  destination:
    name: c-omer-okullari        # Rancher cluster name
    namespace: lumix-app
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - PruneLast=true
      - ServerSideApply=true
    retry:
      limit: 5
      backoff:
        duration: 30s
        factor: 2
        maxDuration: 5m
```

### 6.2. Seed playbook `05-customer-seed.yml`

```yaml
- name: Customer seed orkestrasyonu
  hosts: localhost
  connection: local
  gather_facts: false
  vars:
    customer_id: "{{ lookup('env', 'CUSTOMER_ID') }}"
    profile: "{{ lookup('file', 'customers/' ~ customer_id ~ '/profile.yaml') | from_yaml }}"

  pre_tasks:
    - name: Status marker — başlangıç
      community.hashi_vault.vault_kv2_write:
        path: "secret/lumix/onboarding/{{ customer_id }}/status"
        data: { layer4_seed_started_at: "{{ ansible_date_time.iso8601 }}" }

  tasks:
    - name: Vault path seed
      include_role: { name: customer-seed-vault }
      tags: [vault, seed]

    - name: Kafka topics
      include_role: { name: customer-seed-kafka }
      tags: [kafka, seed]

    - name: Keycloak realm (opsiyonel)
      include_role: { name: customer-seed-keycloak }
      when: profile.keycloak_enabled | default(false)
      tags: [keycloak, seed]

    - name: PostgreSQL ilk tenant
      include_role: { name: customer-seed-postgres }
      tags: [postgres, seed]

    - name: Identity-service ilk admin user
      include_role: { name: customer-seed-identity }
      tags: [identity, seed]

    - name: License aktivasyonu
      include_role: { name: customer-seed-license }
      tags: [license, seed]

  post_tasks:
    - name: Status marker — bitiş
      community.hashi_vault.vault_kv2_write:
        path: "secret/lumix/onboarding/{{ customer_id }}/status"
        data: { layer4_seed_completed_at: "{{ ansible_date_time.iso8601 }}" }
```

### 6.3. Identity-service admin user seed role

```yaml
# roles/customer-seed-identity/tasks/main.yml
- name: İlk admin user için API çağrısı
  ansible.builtin.uri:
    url: "https://api.{{ customer_id }}.lumix.io/api/v1/admin/users/bootstrap"
    method: POST
    headers:
      Content-Type: application/json
      X-Bootstrap-Token: "{{ lookup('community.hashi_vault.vault_kv2_get', 'secret/lumix/' ~ customer_id ~ '/identity').secret.bootstrap_token }}"
    body_format: json
    body:
      email: "{{ profile.contacts.primary_admin.email }}"
      name: "{{ profile.contacts.primary_admin.name }}"
      tenant_id: "00000000-0000-0000-0000-000000000001"
      role: "tenant-admin"
      send_invitation: true
    status_code: [200, 201, 409]   # 409 = already exists (idempotent)
  register: bootstrap_result
  no_log: true

- name: Sonuç logla
  ansible.builtin.debug:
    msg: "Admin user provisioned: {{ profile.contacts.primary_admin.email }}"
```

### 6.4. License seed

```yaml
# roles/customer-seed-license/tasks/main.yml
- name: License dosyasını üret (License Generator API)
  ansible.builtin.uri:
    url: "https://license-generator.lumix.io/api/v1/licenses"
    method: POST
    headers:
      Authorization: "Bearer {{ lookup('community.hashi_vault.vault_kv2_get', 'secret/lumix/license-generator').secret.api_token }}"
      Content-Type: application/json
    body_format: json
    body:
      customer_id: "{{ customer_id }}"
      tenants_max: "{{ profile.license.tenants_max }}"
      modules_enabled: "{{ profile.license.modules }}"
      valid_until: "{{ profile.license.valid_until }}"
      features: "{{ profile.license.features | default([]) }}"
  register: license_response
  no_log: true

- name: License dosyasını cluster'a deploy et (license-service Secret)
  ansible.builtin.uri:
    url: "https://api.{{ customer_id }}.lumix.io/api/v1/admin/license"
    method: PUT
    headers:
      Authorization: "Bearer {{ lookup('community.hashi_vault.vault_kv2_get', 'secret/lumix/' ~ customer_id ~ '/identity').secret.bootstrap_token }}"
      Content-Type: application/json
    body_format: json
    body:
      license_jwt: "{{ license_response.json.license_jwt }}"
    status_code: 200
```

### 6.5. Smoke test detayı

```bash
#!/usr/bin/env bash
set -euo pipefail

CUSTOMER_ID="$1"
BASE="https://api.${CUSTOMER_ID}.lumix.io"

echo "[1/5] Health endpoint..."
curl -fs $BASE/actuator/health | jq -e '.status == "UP"'

echo "[2/5] Login..."
TOKEN=$(curl -s -X POST $BASE/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"bootstrap@${CUSTOMER_ID}.lumix.io\",\"password\":\"${BOOTSTRAP_PASSWORD}\"}" \
  | jq -r .accessToken)
test -n "$TOKEN"

echo "[3/5] Tenant list..."
curl -fs -H "Authorization: Bearer $TOKEN" $BASE/api/v1/admin/tenants | jq -e 'length > 0'

echo "[4/5] License status..."
curl -fs -H "Authorization: Bearer $TOKEN" $BASE/api/v1/admin/license | jq -e '.valid == true'

echo "[5/5] Sample academic endpoint..."
curl -fs -H "Authorization: Bearer $TOKEN" $BASE/api/v1/academic/health

echo "Smoke test PASS for ${CUSTOMER_ID}"
```

## 7. Dikkat edilecek tuzaklar

- **Layer marker'larına güvenirken layer'ın gerçek state'ini doğrulamamak**: marker var ama Terraform state corrupt. Periyodik verify (terraform refresh, ansible ping, argocd app diff).
- **Manuel intervention'ı zorunlu bir layer'a koymadan production'a çalıştırmak**: kazara onboarding 30 müşteri başlatır → bütçe felaketi. Layer 1 mutlaka manuel approve.
- **Inventory output'unu artifact olarak geçirmemek**: Layer 1 → Layer 2 IP listesi kaybolur. CI artifacts kullan.
- **Vault token TTL kısa**: 1h sonra pipeline ortasında token expire. CI variable `VAULT_TOKEN` renew job veya AppRole + auto-renewal.
- **Smoke test bypass etmek**: "manuel test ederim" → tutarsızlık. Smoke test pipeline'a zorunlu gate.
- **Customer bildirim'ini erken göndermek**: Layer 4 smoke FAIL ama müşteriye "ready" denildi → güven kaybı. Notify sadece smoke pass sonrası.
- **Idempotent olmayan API call (license oluştur tekrar)**: ikinci kez aynı license JWT döner mi yoksa duplicate mi? API design tarafında: same input → same license (deterministic veya 409 + verify).
- **Multi-region müşteride DNS TTL yetersiz**: ilk apply'da Cloudflare TTL 300s; smoke test 5 dakika beklemeden başlarsa NXDOMAIN. Pipeline retry + DNS propagation wait.
- **Rancher cluster id'nin Vault'a yazılmaması**: future ops için kaybolur. Layer 2 sonunda Vault'a `secret/lumix/{cid}/rancher/cluster_id` yaz.
- **ArgoCD sync timeout düşük**: büyük cluster ilk deploy 30+ dakika. `--timeout 1800` veya değiştirilebilir.
- **Layer ayrımına uymayan task** (örn. seed'in Terraform output kullanması): CI dependency graph kırılır. Layerlar arası iletişim sadece Vault marker veya artifacts.

## 8. Diğer konularla ilişkisi

- [Terraform Basics](./01-terraform-basics.md) — Layer 1
- [Ansible Basics](./02-ansible-basics.md) — Layer 2 ve Layer 4
- [License Management](./04-license-management.md) — Layer 4 license seed
- [K3s](../infra-devops/02-k3s-lightweight-k8s.md) — Layer 2 K3s install
- [Rancher Multi-Cluster](../infra-devops/04-rancher-multi-cluster.md) — cluster import
- [ArgoCD GitOps](../21-ci-cd/04-argocd-gitops.md) — Layer 3 application
- [GitLab CI Pipelines](../21-ci-cd/02-gitlab-ci-pipelines.md) — CI orchestration
- [Velero Backup](../infra-devops/09-velero-backup.md) — Layer 2 addon olarak Velero kurulur

## 9. Daha derine inmek için

- HashiCorp "Multi-step deployments" patternleri
- "Continuous Delivery" — Jez Humble (pipeline tasarımı)
- "Site Reliability Engineering" — Google (provisioning automation)
- GitLab CI/CD Templates
- Search keyword'leri: *"resumable deployment pipeline"*, *"idempotent provisioning"*, *"layered infrastructure automation"*, *"customer onboarding automation"*, *"argocd applicationset multi-cluster"*

## 10. Sözlük

- **Onboarding**: Yeni müşterinin sistemine alınma süreci.
- **Layer**: Pipeline'ın mantıksal aşaması (infra, OS, app, seed).
- **Idempotent**: Aynı operasyonu N kere uygulamak aynı sonuca götürür.
- **Resumable**: Yarıda kalan operasyonu kaldığı yerden devam ettirme.
- **Marker**: Pipeline state izleme için anchor (Vault path / artifact).
- **Smoke test**: Sistemin temel davranışını doğrulayan hızlı test.
- **Rollback**: Hata durumunda önceki state'e dönmek.
- **Manuel gate**: Pipeline'da insan onayı bekleyen adım.
- **Bootstrap user / token**: İlk kurulumda kullanılan, sonra rotate edilen geçici credential.
- **Customer profile**: Müşterinin özellikleri (tier, region, modules, contacts) tek YAML.
- **License JWT**: RS256-signed lisans token'ı; offline doğrulanır.
- **Tier**: Müşteri boyut sınıfı (xs/s/m/l).
- **Self-service portal**: Müşterinin kendi onboarding'i tetiklediği UI (Lumix gelecek özellik).
