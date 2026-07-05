---
title: Rancher Manager — Multi-Cluster Yönetim
description: Rancher Manager nedir, Lumix'in müşteri başına cluster mimarisinde Rancher rolü, merkezi UI, Fleet GitOps, RBAC, cluster import vs provision.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

Lumix'in **müşteri başına izole K8s cluster** kararı operasyonel olarak şu soruyu doğurur: "Şimdi 30 cluster'ı **nasıl** yöneteceğim?" Cevap: **Rancher Manager**. Bu sayfa Rancher'ı sıfırdan anlatır, "downstream cluster" kavramını netleştirir, Lumix'in **müşteri = downstream cluster** modelini gösterir, cluster import vs provision ayrımını açıklar, Fleet GitOps + ArgoCD ilişkisini netleştirir, RBAC modelini ve System Upgrade Controller kullanımını detaylandırır. Hedef kitle: K8s ve Helm temellerini bilen ([Kubernetes Temelleri](./01-kubernetes-fundamentals.md), [Helm Charts](./03-helm-charts.md)) ekip lideri / DevOps.

## 1. Bu nedir? (Sıfırdan)

**Rancher Manager**, SUSE (eski Rancher Labs) tarafından geliştirilen, **birden fazla Kubernetes cluster'ını tek bir kontrol noktasından yöneten** open-source platform. Lisanslı sürümü de var ama Rancher OSS production kullanım için yeterli.

İki rol içerir:
- **Local cluster (management cluster)**: Rancher Manager'ın kendisinin yaşadığı cluster. Genellikle ufak (3 küçük node).
- **Downstream cluster**: Yönetilen cluster'lar. Lumix'te = **her müşteri cluster'ı**.

Sağladığı özellikler:
- Tek UI ile tüm downstream cluster'ları görme (`kubectl`'in 30 farklı context'ini tek arayüzde)
- Cluster **provision** (Rancher kursun) veya **import** (önceden kurulu cluster'ı tak)
- Multi-cluster **RBAC** (kim hangi cluster'da ne yapabilir)
- **Fleet** ile GitOps multi-cluster deployment
- **Cluster Templates** (RKE2/K3s/EKS/AKS standart konfigürasyon)
- **System Upgrade Controller** (CRD-driven K3s upgrade)
- **Monitoring/Logging** entegrasyon (Prometheus/Grafana paketleri)
- **Backup/Restore** (rancher-backup operator)
- **Catalog** (Helm chart marketplace)

### Günlük hayattan analoji

Bir okul müdürü 30 sınıfı yönetiyor: her sınıfa tek tek gitmek (30 farklı `kubectl context`) vs müdür odasında **tek konsoldan** her sınıfın durumunu, ders programını, öğretmen listesini görmek (Rancher). Aynı zamanda yeni sınıf açmak (cluster provision) veya başka müdürün açtığı sınıfı sisteme katmak (cluster import) mümkün.

## 2. Hangi problemi çözüyor?

Lumix sağlayıcı olarak **30 müşteri** olduğunu varsayalım. Rancher'sız operasyon:

| Acı | Rancher'sız | Rancher'lı |
|---|---|---|
| Cluster listesini gör | 30 farklı kubeconfig | Tek UI |
| Belirli bir müşteride hangi pod CrashLoop'ta? | SSH + kubectl × her seferinde | Search UI'da pod adı |
| K3s upgrade'i tüm cluster'lara dağıt | 30 Ansible run | Rancher Plan CRD veya Fleet |
| Müşteri DevOps ekibine sadece kendi cluster'ında erişim | Manuel ServiceAccount + kubeconfig | Rancher User + Cluster RoleTemplate |
| Yeni cluster bootstrap | Terraform + Ansible + manuel addon | Cluster Template ile tek tık |
| Monitoring stack'i tüm cluster'lara kur | Manuel Helm 30× | Fleet GitBundle |
| Bir cluster sağlığı dashboard | Manuel Grafana datasource × 30 | Rancher Observability integration |

### Patlamış üretim hikayesi

Bir takım 12 müşterilik K3s filosunu kubeconfig dosyalarıyla yönetiyordu. Yeni iş yetkilendirmesi geldiğinde: 12 dosya × elle düzenle × sızıntı riski. Bir müşteri cluster sertifikası süresi geçti, kimse fark etmedi → 18 saatlik outage. Rancher kurulu olsaydı: tek UI, cert expiry alert. Lumix bu acıyı baştan dışlar.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Genel mimari

```
┌──────────────────────────────────────────────────────────────────┐
│         LUMIX (sağlayıcı) — Management Cluster                   │
│                                                                  │
│   ┌────────────────────────────────────────────────────────────┐ │
│   │ Rancher Manager (Helm chart deploy)                        │ │
│   │  - rancher-webhook, rancher-server, rancher-fleet          │ │
│   │  - Kendi etcd / SQL backend                                │ │
│   │  - UI: https://rancher.lumix.io                            │ │
│   └─────────────────────────┬──────────────────────────────────┘ │
│                             │                                    │
│   Local cluster RBAC, audit log, backup                          │
└─────────────────────────────┼────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│ DOWNSTREAM      │  │ DOWNSTREAM      │  │ DOWNSTREAM      │
│ omer-okullari   │  │ x-vakfi         │  │ y-okul          │
│ K3s 1.30        │  │ K3s 1.30        │  │ K3s 1.30        │
│  └─ cattle-cluster-agent ─┐                                │
│  └─ cattle-node-agent ──┐ │ websocket tunnel to mgmt       │
└─────────────────────────┴─┴────────────────────────────────┘
```

Downstream cluster'larda **iki agent pod**'u kurulur:
- **`cattle-cluster-agent`** (Deployment): cluster-level operasyonlar (apiserver proxy, audit).
- **`cattle-node-agent`** (DaemonSet): node bazlı operasyonlar (log, drain).

Agent'lar Rancher'a **outbound websocket tunnel** açar — Rancher müşteri cluster'ına IP-level erişime ihtiyaç duymaz. Müşteri firewall'ları açısından ideal.

### 3.2. Cluster ekleme yöntemleri

**(a) Import existing cluster** (Lumix tercih ettiği yöntem):
1. Önceden Ansible+Terraform ile K3s kurulur.
2. Rancher UI'da "Add Existing Cluster" → tek satır komut üretir.
3. Müşteri cluster'ında o komut çalıştırılır → agent kurulur.
4. Tunnel açılır, cluster Rancher UI'da görünür.

**(b) Provision new cluster**:
- Rancher provider integration (vSphere, EC2, DigitalOcean, custom).
- Rancher SSH/cloud-init ile node hazırlar, K3s/RKE2 kurar.
- Lumix bu yolu **kullanmaz** çünkü Lumix kendi Terraform+Ansible pipeline'ına sahiptir (bkz. [Customer Onboarding Pipeline](../20-iac-provisioning/03-customer-onboarding-pipeline.md)).

### 3.3. Fleet (GitOps multi-cluster)

Fleet, Rancher'ın **multi-cluster GitOps controller**'ı. Mantığı:
- **GitRepo CRD**: bir Git URL'i izlenir.
- **Bundle**: o repo'daki manifest/Helm chart paketleri.
- **Cluster Group**: hangi cluster'lara uygulanacak (label selector).

Lumix'te Fleet **opsiyoneldir**. Resmi GitOps aracımız **ArgoCD**'dir; Fleet sadece Rancher'ın kendi addon'larını yaymak için kullanılır (monitoring, logging operator vb.).

### 3.4. System Upgrade Controller

Rancher tarafından geliştirilen, downstream cluster'lardaki K3s versiyonunu CRD ile yöneten controller.

```yaml
apiVersion: upgrade.cattle.io/v1
kind: Plan
metadata:
  name: k3s-server-upgrade
  namespace: system-upgrade
spec:
  concurrency: 1
  version: v1.30.5+k3s1
  nodeSelector:
    matchExpressions:
      - { key: node-role.kubernetes.io/control-plane, operator: Exists }
  serviceAccountName: system-upgrade
  cordon: true
  upgrade:
    image: rancher/k3s-upgrade
```

Plan apply edilince: ilgili node'da Job çalışır, K3s binary değişir, systemd restart.

### 3.5. RBAC modeli

Rancher RBAC iki katman:
- **Global rules**: Lumix admin'leri, audit reader, restricted-admin.
- **Cluster/Project roles**: Müşteri cluster'larında bir kullanıcının yetkileri.

Cluster RoleTemplate'leri:
- `cluster-owner` (her şey)
- `cluster-member` (genel görüntü, deploy)
- `read-only`
- Custom: `lumix-support-engineer` (sadece log + describe; secret okuma yok)

Bağlanma:
```yaml
apiVersion: management.cattle.io/v3
kind: ClusterRoleTemplateBinding
clusterName: c-omer-okullari
userPrincipalName: keycloak_user://oner@onerbilisim.com
roleTemplateName: cluster-owner
```

### 3.6. Authentication

Rancher local auth dışında SSO destekler:
- **Keycloak (OIDC)** — Lumix'in tercihi
- LDAP/Azure AD/GitHub/Google
- SAML

Lumix kararı: Rancher → Keycloak (master realm: `lumix-staff`). Yeni mühendis girince Keycloak'ta grup verilir; Rancher otomatik yetkilendirir.

### 3.7. Cluster Templates

Lumix kendi cluster template'ini yayınlar: K3s versiyonu, addon listesi (Calico, Velero, cert-manager, ESO, Prometheus stack). Yeni müşteri bootstrap'inde bu template referans alınır → standart cluster.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Rancher Manager nerede yaşıyor?

**Lumix-internal cluster** (Lumix sağlayıcı tarafı). Üç node K3s. Aynı cluster'da yaşayan bileşenler:
- Rancher Manager
- GitLab CE
- ArgoCD (Lumix-internal)
- Vault master
- Apicurio Registry
- License Generator
- Internal Admin Panel

(Bu cluster da Rancher tarafından "local" cluster olarak yönetilir — self-managed.)

### 4.2. Müşteri cluster ekleme akışı

```
1. Terraform → VPS provisioning (Hetzner/AWS/DO/On-prem)
2. Ansible → OS hardening + K3s install (HA topoloji)
3. Ansible → cattle-cluster-agent kurulum (Rancher import komutu çalıştırılır)
4. Rancher UI'da cluster "Active" görünür
5. ArgoCD Application oluşturulur (lumix-platform chart deploy)
6. Ansible → customer seed (Keycloak realm, Kafka topic, Vault seed)
7. Hazır
```

Detay: [Customer Onboarding Pipeline](../20-iac-provisioning/03-customer-onboarding-pipeline.md).

### 4.3. Cluster naming convention

```
c-{installation-id}              # Rancher cluster ID
  örn: c-omer-okullari, c-x-vakfi

Cluster labels:
  lumix.io/installation-id=omer-okullari
  lumix.io/tier=m
  lumix.io/region=tr-istanbul
  lumix.io/created-at=2026-04-15
  lumix.io/k3s-version=v1.30.4+k3s1
```

Bu label'lar Fleet selector, audit query ve dashboard filter için kullanılır.

### 4.4. RBAC matris (Lumix tarafı)

| Rol | Erişim |
|---|---|
| `lumix-platform-admin` | Tüm cluster owner |
| `lumix-support-l2` | Tüm cluster member (deploy hariç) |
| `lumix-support-l1` | Tüm cluster read-only + log read |
| `lumix-security-auditor` | Tüm cluster audit log read |
| `customer-admin-{id}` | Sadece kendi cluster'ı owner |

Customer admin'i genelde Rancher UI'ya **direkt erişmez**; gerekirse "Lumix Internal Admin Panel" delege eder. Rancher erişimi sadece Lumix staff'a.

### 4.5. Audit log

Rancher kendi audit log'unu üretir; ayrıca downstream K3s'in audit log'una (`/var/log/k3s-audit.log`) erişim. Lumix kuralı:
- Audit log Promtail ile Loki'ye gönderilir.
- 90 gün ılık tutulur, sonra RustFS soğuk arşive.
- Audit reader rolü dışında kimse log silemez.

### 4.6. Cluster sertifikası rotation

Rancher otomatik **internal CA rotation** sunar. Lumix politikası:
- Internal CA: 5 yıl
- Cluster cert: 1 yıl, 60 gün öncesinden auto-rotate
- cert-manager ile mTLS sertifikaları ayrı yönetilir ([cert-manager](./08-cert-manager-tls.md)).

### 4.7. Backup

`rancher-backup` operator local cluster'da kurulur:
```yaml
apiVersion: resources.cattle.io/v1
kind: Backup
metadata:
  name: rancher-daily
spec:
  resourceSetName: rancher-resource-set
  schedule: "0 2 * * *"   # her gece 02:00
  retentionCount: 14
  storageLocation:
    s3:
      bucketName: lumix-rancher-backup
      endpoint: rustfs.lumix.io
      region: tr-istanbul
      credentialSecretName: rancher-s3
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Lens IDE** | Sadece istemci-side, RBAC yok, audit yok. Geliştirici yardımcı aracı olarak kullanılır ama merkezi yönetim değil. |
| **Headlamp** | Open-source UI; multi-cluster ama RBAC/onboarding/Fleet gibi platform özellikleri yok. |
| **OpenShift** | Lisans + Red Hat ekosistem kilidi. |
| **Plain Argo CD + custom dashboard** | GitOps var ama cluster bootstrap, RBAC, audit, monitoring entegrasyonu kendin yazmalısın. |
| **Cluster API (CAPI)** | Cluster lifecycle güçlü ama UI/dashboard yok; Rancher zaten CAPI desteği sunuyor. |
| **EKS multi-account console** | Bulut-kilit; on-prem müşteri için geçerli değil. |

### Kabul ettiğimiz trade-off'lar

- **Vendor (SUSE) bağımlılığı**: Rancher OSS Apache 2.0, fork edilebilir. Riskli görülmüyor.
- **Local cluster ek yük**: Rancher Manager kendi 3-node cluster'ında yaşıyor. Bu ek altyapı kabul.
- **Fleet vs ArgoCD karışıklığı**: Lumix Fleet'i sadece Rancher addon'ları için kullanır; application GitOps ArgoCD'dedir. Bu sınırı net çizmek operasyonel disiplin gerektirir.

### Tekrar değerlendirme tetikleyicileri

- Müşteri sayısı 200+ olunca local cluster ölçeklenebilir mi? (Test gerekir.)
- Rancher 3 (varsa) bozucu değişim getirirse upgrade stratejisi.
- Müşteri kendi Rancher'ını çalıştırmak isterse (white-label) → multi-tenant Rancher mı, ayrı instance mı?

## 6. Pratik örnek

### 6.1. Rancher Manager kurulum (Helm)

```bash
helm repo add rancher-stable https://releases.rancher.com/server-charts/stable
helm repo update

kubectl create namespace cattle-system

# cert-manager kurulu olmalı (önce)
helm install rancher rancher-stable/rancher \
  --namespace cattle-system \
  --version 2.9.0 \
  --set hostname=rancher.lumix.io \
  --set ingress.tls.source=letsEncrypt \
  --set letsEncrypt.email=ops@lumix.io \
  --set replicas=3 \
  --set bootstrapPassword=$(openssl rand -base64 32)
```

İlk girişte bootstrap password ile login; sonra Keycloak OIDC ayarı.

### 6.2. Mevcut cluster import etme

Rancher UI → Cluster Management → Create → Import Existing → cluster adı (`c-omer-okullari`) + label'lar → "Create".

Sonuç:
```bash
kubectl apply -f https://rancher.lumix.io/v3/import/9k4xx...yaml
```

Bu komut müşteri cluster'ında çalıştırılır. Ansible role'ü:

```yaml
# roles/rancher-import/tasks/main.yml
- name: Rancher import URL'i al
  ansible.builtin.uri:
    url: "https://rancher.lumix.io/v3/clusterregistrationtokens"
    method: POST
    headers:
      Authorization: "Bearer {{ rancher_api_token }}"
      Content-Type: application/json
    body_format: json
    body:
      type: clusterRegistrationToken
      clusterId: "{{ rancher_cluster_id }}"
    status_code: 201
  register: token_response
  no_log: true

- name: Cluster agent yaml uygula
  ansible.builtin.shell: |
    kubectl apply -f {{ token_response.json.manifestUrl }}
  environment:
    KUBECONFIG: /etc/rancher/k3s/k3s.yaml
```

### 6.3. K3s sıralı upgrade Plan'ı

```yaml
apiVersion: upgrade.cattle.io/v1
kind: Plan
metadata:
  name: k3s-server-upgrade
  namespace: system-upgrade
spec:
  concurrency: 1
  version: v1.30.5+k3s1
  nodeSelector:
    matchExpressions:
      - { key: node-role.kubernetes.io/control-plane, operator: Exists }
  serviceAccountName: system-upgrade
  cordon: true
  drain:
    force: true
    ignoreDaemonSets: true
    deleteEmptydirData: true
  upgrade:
    image: rancher/k3s-upgrade
---
apiVersion: upgrade.cattle.io/v1
kind: Plan
metadata:
  name: k3s-agent-upgrade
  namespace: system-upgrade
spec:
  concurrency: 1
  version: v1.30.5+k3s1
  nodeSelector:
    matchExpressions:
      - { key: node-role.kubernetes.io/control-plane, operator: DoesNotExist }
  prepare:
    args: ["prepare", "k3s-server-upgrade"]   # server bitsin diye
    image: rancher/k3s-upgrade
  serviceAccountName: system-upgrade
  cordon: true
  drain:
    force: true
    ignoreDaemonSets: true
  upgrade:
    image: rancher/k3s-upgrade
```

### 6.4. RBAC bağlama

```yaml
apiVersion: management.cattle.io/v3
kind: ClusterRoleTemplateBinding
metadata:
  name: oner-omer-okullari-owner
  namespace: c-omer-okullari
clusterName: c-omer-okullari
userPrincipalName: keycloak_user://oner@onerbilisim.com
roleTemplateName: cluster-owner
```

### 6.5. Fleet GitRepo örneği (Rancher addon yayma)

```yaml
apiVersion: fleet.cattle.io/v1alpha1
kind: GitRepo
metadata:
  name: lumix-cluster-addons
  namespace: fleet-default
spec:
  repo: https://gitlab.lumix.io/platform/cluster-addons.git
  branch: main
  paths:
    - calico/
    - velero/
    - external-secrets/
  targets:
    - name: tier-m-and-l
      clusterSelector:
        matchExpressions:
          - { key: lumix.io/tier, operator: In, values: ["m", "l"] }
```

Tier `m` ve `l` cluster'larına Calico/Velero/ESO otomatik yayılır.

### 6.6. Rancher backup verify

```bash
kubectl get backups.resources.cattle.io
# NAME            BACKUPTYPE   SCHEDULE      RETENTIONCOUNT   STATUS
# rancher-daily   Recurring    0 2 * * *     14               Completed
```

## 7. Dikkat edilecek tuzaklar

- **Rancher Manager'ı yönetim cluster'ı dışına koymak**: cluster çökerse Rancher de gider, geri kalan downstream'ler erişilemez (UI yok, manuel kubectl). Lumix kuralı: management cluster 3-node HA + ayrı backup.
- **Local cluster'da Rancher chart'ını ArgoCD ile yönetmek**: ArgoCD self-managed → kafa karışıklığı. Rancher Helm release'i `helm`'le yönetilir; ArgoCD Lumix application'ları için.
- **Cluster import + ArgoCD apply çakışması**: ArgoCD bir kaynağı yönetiyorsa Rancher UI'dan değiştirmek drift yaratır. Lumix kuralı: bir kaynağı kim yönetiyorsa **sadece o** değiştirir. Annotation: `argocd.argoproj.io/sync-options: ServerSideApply=true`.
- **Multi-cluster RBAC'i kafa karıştırıcı tutmak**: rol isimlendirmesi tutarsız olursa hangi rolün ne yaptığı kaybolur. Lumix template katalog ile zorunlu standardize.
- **Keycloak SSO'yu kurmadan public erişim**: bootstrap password unutulur, Rancher dışa açık kalır → güvenlik açığı. İlk gün OIDC config + bootstrap password disable.
- **Fleet ve ArgoCD'yi aynı kaynak için kullanmak**: iki controller döngüye girer (biri push, diğeri revert). Net sorumluluk ayrımı: Fleet → cluster bootstrap addon'ları, ArgoCD → application.
- **System Upgrade Controller'ı uyumsuz Plan ile çalıştırmak**: server upgrade tamamlanmadan agent'ı başlatmak → cluster yarısı eski, yarısı yeni. `prepare.args` ile bağımlılık kur.
- **Cluster registration token'ı sızdırmak**: bu token ile herhangi bir cluster Rancher'a register olabilir. Secret yönetimi (Vault).
- **Audit log Loki'ye gönderilmemesi**: incident sonrası kanıt yok. Promtail config kontrol.
- **`cattle-cluster-agent` pod restart loop**: genelde DNS/TLS sebebi. Rancher hostname'i downstream cluster'dan resolve edilebilmeli + sertifika trust chain'i tamam olmalı.
- **Cluster import sonrası secret görünürlüğü**: cluster-owner role downstream'in tüm secret'larını görür. Customer cluster'larında Lumix staff'a `cluster-member-restricted` (secret read yasak) tercih edilir.

## 8. Diğer konularla ilişkisi

- [Kubernetes Temelleri](./01-kubernetes-fundamentals.md) — cluster yapısı
- [K3s](./02-k3s-lightweight-k8s.md) — downstream cluster dağıtımı
- [Helm Charts](./03-helm-charts.md) — Rancher chart deploy
- [ArgoCD GitOps](../21-ci-cd/04-argocd-gitops.md) — application GitOps (Fleet ile sınır)
- [cert-manager TLS](./08-cert-manager-tls.md) — Rancher TLS sertifikası
- [Velero Backup](./09-velero-backup.md) — downstream cluster backup
- [Customer Onboarding Pipeline](../20-iac-provisioning/03-customer-onboarding-pipeline.md) — Rancher import adımı
- [License Management](../20-iac-provisioning/04-license-management.md) — yeni cluster + lisans birleştirme

## 9. Daha derine inmek için

- Resmi doc: [https://ranchermanager.docs.rancher.com/](https://ranchermanager.docs.rancher.com/)
- Fleet doc: [https://fleet.rancher.io/](https://fleet.rancher.io/)
- System Upgrade Controller: [https://github.com/rancher/system-upgrade-controller](https://github.com/rancher/system-upgrade-controller)
- Rancher Academy ücretsiz kursları
- Search keyword'leri: *"rancher cluster import vs provision"*, *"rancher fleet vs argocd"*, *"rancher rbac roletemplate"*, *"system-upgrade-controller plan"*
- Lumix engineering-notes referansı: `21-kubernetes-helm-infra-devops-engineering-guide.md`

## 10. Sözlük

- **Rancher Manager**: Multi-cluster K8s yönetim platformu (SUSE/Rancher).
- **Local cluster (management cluster)**: Rancher Manager'ın kendisinin yaşadığı cluster.
- **Downstream cluster**: Rancher tarafından yönetilen cluster (Lumix'te = müşteri cluster'ı).
- **`cattle-cluster-agent`**: Downstream cluster'da çalışan, Rancher'a tunnel açan Deployment.
- **`cattle-node-agent`**: Downstream cluster'da her node'da çalışan DaemonSet.
- **Cluster Template**: Yeni cluster'lar için Rancher'ın hazır konfigürasyon profili.
- **Cluster Registration Token**: Yeni cluster'ın Rancher'a katılırken kullandığı tek seferlik sır.
- **Fleet**: Rancher'ın multi-cluster GitOps controller'ı.
- **GitRepo CRD**: Fleet'in izlediği Git repository tanımı.
- **Bundle**: Fleet'in Git'ten ürettiği uygulamanabilir manifest paketi.
- **System Upgrade Controller**: K3s/RKE2 sıralı upgrade için CRD-driven controller.
- **`Plan` CRD**: Upgrade'in zaman/şekil tanımı.
- **rancher-backup operator**: Rancher state backup/restore operatörü.
- **ClusterRoleTemplate**: Rancher'ın tek noktadan tanımladığı cluster yetki şablonu.
- **ClusterRoleTemplateBinding**: Bir kullanıcıya cluster bazlı rol verme nesnesi.
