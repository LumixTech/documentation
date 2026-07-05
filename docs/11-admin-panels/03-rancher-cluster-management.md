---
title: Rancher Cluster Management
description: Lumix DevOps multi-cluster K8s yönetimi — Rancher Manager ile müşteri cluster ekleme, ArgoCD app deploy, pod/node monitoring, network policy, RBAC.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Lumix DevOps ekibi, **her müşterinin kendi K8s cluster'ı**na nasıl bakar, yönetir? Bu sayfa şunları anlatır:

- Multi-cluster Kubernetes yönetimi neden zor
- Rancher Manager nedir, nasıl çalışır
- Lumix'in Rancher kurulumu (server tarafı + agent'lar)
- Yeni müşteri cluster'ı Rancher'a kayıt
- ArgoCD ile birlikte kullanım (Rancher = view + RBAC; ArgoCD = deployment GitOps)
- Pod / node monitoring, kubectl proxy
- Network policy ve RBAC her cluster'da
- Rancher UI ile Internal Admin Panel ilişkisi

Bu sayfa **DevOps run-book'larının** önsözü; kim Rancher kullanacaksa burdan başlasın.

## 1. Rancher nedir? (Sıfırdan)

**Rancher**, SUSE'nin (eski adı Rancher Labs) açık kaynak **multi-cluster Kubernetes yönetim platformudur**. Birden fazla K8s cluster'ı tek UI'dan görmenizi, yönetmenizi, monitor etmenizi sağlar.

### Günlük hayattan analoji

Bir otel zinciri yöneticisi düşün:

- **Her otel** = bir K8s cluster (müşteri başına bir cluster)
- **Otel yönetim sistemi (PMS)** = kubectl + manuel SSH (her otele ayrı bağlanırsın)
- **Zincir yönetim merkezi (Rancher)** = tek ekrandan tüm otellerin durumu: kaç oda dolu, kaç çalışan vardiyada, hangi otelde alarm
- **Otelin kendi resepsiyonu** = K8s cluster'ın kendi kontrol-plane'i; tek başına çalışıyor, sadece raporlama merkezi

Rancher otel **işletmesini ele almaz**; sadece **görünürlük + standardizasyon + RBAC** sağlar.

### Bileşenler

```
Rancher Manager (server)
  ↓ HTTPS websocket (agent → server tünel)
Rancher Agent (her cluster'da pod olarak)
  ↓ kube-api proxy
K8s API server (cluster'ın kendi)
```

Rancher Manager bir Kubernetes cluster üstünde çalışır (Lumix'te ayrı management cluster). Her müşteri cluster'ına **agent kurulur**; agent merkeze geri bağlanır (NAT arkasındaki cluster'lar da çalışsın diye reverse tunnel).

## 2. Hangi problemi çözüyor?

Rancher olmadan:

- 10 müşteri cluster → 10 farklı `kubeconfig`, 10 `kubectl` context
- Her cluster'a SSH/jumpbox erişimi gerekli
- "Şu an müşteri X'in cluster'ında pod'lar nasıl?" sorusu için context switch + komut
- RBAC her cluster'da ayrı (kullanıcıyı 10 cluster'a 10 ayrı YAML ile ekle)
- Cross-cluster query yok ("tüm cluster'larımda kaç pod restart oldu son saatte?")
- Audit log her cluster'da ayrı

Rancher ile:

- **Tek UI'dan tüm cluster'lar**: tıkla, geç
- **Centralized RBAC**: bir Lumix DevOps kullanıcısını tek yerden tüm cluster'lara ekle
- **Built-in monitoring**: cluster health, node, pod, deployment
- **Cluster provisioning** (opsiyonel): RKE2 / K3s ile yeni cluster aç
- **Catalog (Apps)**: Helm chart'larını cluster'lara deploy etme arayüzü
- **Fleet** (GitOps): cluster'lara declarative deploy (ArgoCD alternatifi)
- **Cattle**: pod/node monitoring agent

## 3. Nasıl çözüyor? (Mekanizma)

### 3.1. Cluster register akışı

```
1. Lumix DevOps: Rancher UI → "Add Cluster" → "Import existing"
2. Rancher: registration manifest üretir
   (her import için unique cluster_id + token)
3. DevOps: o müşteri cluster'ında
   $ kubectl apply -f https://rancher.lumix.io/v3/import/<token>.yaml
4. Rancher Agent pod o cluster'a düşer
5. Agent: outbound websocket → Rancher Manager
6. Rancher Manager: agent ile handshake → cluster authorized
7. Rancher UI'da cluster listede gözükür ("Active" status)
```

### 3.2. RBAC senkronizasyonu

Lumix kullanıcı `oner@onerbilisim.com` Rancher'a Keycloak SSO ile giriş.

Rancher'da kullanıcının role'leri:
- **Restricted Admin** — tüm cluster'larda admin
- **Cluster Owner / Member** — belirli cluster'larda
- **Project Owner / Member** — cluster içinde namespace grupları

Rancher bu role'leri her cluster'a `RoleBinding` / `ClusterRoleBinding` olarak senkronize eder.

### 3.3. Cluster monitoring

Rancher cluster ekleyince **Cluster Tools** menüsünden:
- **Monitoring** (Prometheus + Grafana) deploy edilir o cluster'a
- **Logging** (Banzai Cloud logging operator) → loglar Lumix Loki'ye gönderir
- **Istio** (opsiyonel)
- **Backup** (Velero)

Lumix bu lib'leri ArgoCD ile zaten her cluster'a deploy ediyor; Rancher'a built-in olarak kurmayız (duplicate). Rancher sadece **visibility** veriyor: pod listesi, log tail, exec, port-forward.

### 3.4. Fleet vs ArgoCD

Rancher'ın kendi GitOps tool'u **Fleet** var:
- Pro: Rancher native, multi-cluster ApplicationSet benzeri
- Con: ArgoCD'ye göre küçük ekosistem, ekibin alışkanlığı yok

**Lumix kararı**: **ArgoCD primary** (deployment); **Rancher visibility + RBAC** (görünürlük). Fleet kullanmıyoruz.

## 4. Biz nasıl kullanıyoruz? (Lumix)

### 4.1. Kararlar

| Konu | Karar |
|---|---|
| Tool | **Rancher Manager 2.9+** (LTS) |
| Deployment yeri | **Lumix management cluster** (sağlayıcı tarafı; ayrı K8s) |
| Domain | **`rancher.lumix.io`** (IP allowlist + 2FA) |
| Auth | **Keycloak SSO** (Lumix internal realm) + 2FA |
| Cluster import method | **Import existing K3s** (ayrı tool'la provision; Terraform + Ansible) |
| Cluster provisioning by Rancher | **Hayır** — Lumix Terraform + Ansible kullanır |
| Fleet | **Hayır** — ArgoCD primary GitOps |
| Monitoring | **Visibility için Rancher**; gerçek Prom/Grafana ArgoCD ile her cluster'da |
| Backup | **Velero**, Rancher built-in değil |
| Logs | Rancher'dan log tail; long-term Loki |

### 4.2. Mimari diagram

```
┌──────────────────────────────────────────────────────────────┐
│             Lumix Management Cluster                         │
│                                                              │
│   ┌──────────────────┐    ┌─────────────────┐               │
│   │ Rancher Manager  │    │     ArgoCD      │               │
│   │ (rancher.lumix.. │    │  (argocd.lumix..│               │
│   └──────────────────┘    └─────────────────┘               │
│           ▲                       │                          │
│           │                       │                          │
└───────────┼───────────────────────┼──────────────────────────┘
            │                       │
            │ (websocket            │ (git poll +
            │  tunnel)              │  apply manifest)
            │                       │
            ▼                       ▼
┌──────────────────────────────────────────────────────────────┐
│   Installation N — "Müşteri X" Cluster (K3s)                 │
│                                                              │
│   ┌─────────────────┐                                        │
│   │  Rancher Agent  │  ← reverse tunnel back to Rancher      │
│   └─────────────────┘                                        │
│                                                              │
│   ArgoCD Application "lumix-services":                       │
│   ├─ identity-service          ├─ Kafka                      │
│   ├─ academic-service          ├─ PostgreSQL                 │
│   ├─ ...                       └─ ...                        │
└──────────────────────────────────────────────────────────────┘
```

### 4.3. Yeni müşteri provisioning akışı

```
1. Internal Admin Panel: New Installation Wizard tamamlandı
2. Backend:
   a. Terraform: VPS sipariş (DigitalOcean / Hetzner / OVH)
   b. Ansible: Ubuntu 24.04 LTS install, hardening
   c. Ansible: K3s install + cert-manager
   d. Rancher API: cluster register (token üret)
   e. SSH veya Ansible: cluster'a `kubectl apply -f <import-yaml>`
   f. Rancher cluster "Active"
   g. ArgoCD: ApplicationSet ile yeni cluster'a manifest'leri push
   h. Vault: cluster için sırlar seed
   i. Domain DNS: `app.<customer>.lumix.io` → cluster ingress IP
3. ~30dk içinde cluster çalışıyor
4. Customer Admin invite emaili gider
```

### 4.4. Lumix DevOps günlük kullanım

```
Senaryo: "Müşteri X'te bir pod sürekli restart oluyor."

1. rancher.lumix.io'da login (SSO + 2FA)
2. Clusters → "Müşteri X" tıkla
3. Workloads → Deployments → academic-service
4. "Restarts" sütununda 47 görüyorum (yüksek)
5. Pod log'una tıkla → son hata: "DB connection timeout"
6. Cluster → Storage → PersistentVolumes → DB PVC kontrol
7. Grafana link → DB metric → connection count limit
8. Karar: PgBouncer pod sayısını artır
9. ArgoCD'de `pgbouncer/values.yaml` PR → merge → auto-sync → çözüldü
10. Audit (Rancher built-in): "oner viewed academic-service logs at 14:32"
```

### 4.5. Network policy ile cluster izolasyonu

Her cluster içinde `NetworkPolicy` ile:

- **Default deny** ingress
- **Service-to-service**: sadece tanımlı akış (örn. academic-service → identity-service)
- **Rancher agent**: outbound only (rancher.lumix.io:443)
- **External**: Traefik Ingress → frontend; Kong API Gateway → backend

Rancher cluster içine bakarak network policy YAML'larını gösterir; uygulamayı ArgoCD yapar.

### 4.6. Cluster RBAC (Rancher tarafı)

```yaml
# RancherProject: müşteri içinde namespace grubu
apiVersion: management.cattle.io/v3
kind: Project
metadata:
  name: lumix-services
spec:
  clusterName: customer-x
  containerDefaultResourceLimit:
    cpuLimit: 2000m
    memoryLimit: 4Gi
```

```yaml
# Lumix DevOps role binding (Restricted Admin tüm cluster'larda)
apiVersion: management.cattle.io/v3
kind: GlobalRoleBinding
metadata:
  name: oner-devops
globalRoleName: restricted-admin
userName: oner@onerbilisim.com
```

## 5. Neden Rancher? (Alternatifler)

| Alternatif | Neden elendi |
|---|---|
| **kubectl + multi-context** | UI yok; günlük ops zor; RBAC manuel |
| **Lens IDE** | Desktop client; multi-user paylaşımı zor; audit yok |
| **OpenShift / Red Hat ACM** | RHEL ekosistemi gerekli; lisans maliyeti |
| **Portainer** | K8s desteği zayıf (Docker odaklı) |
| **Headlamp** | Yeni, ekosistem küçük |
| **K9s** (terminal) | DevOps için iyi ama UI/audit yok |
| **Custom Internal Admin'e dashboard yazma** | Tekerlek icat etmek; Rancher zaten var |
| **Rancher** ✅ | OSS, olgun, multi-cluster, RBAC, ekosistem |

### Trade-off

- **Bir başka tool öğrenmek**: ArgoCD + Rancher + Grafana ekibin tool stack'i şişiyor.
- **Resource overhead**: Rancher Manager + agent'ler.
- **Vendor risk**: SUSE'nin Rancher yönü değişirse alternatif (OpenShift, Lens).

### Ne zaman gözden geçiririz?

- 100+ müşteri cluster olursa Rancher scaling karakteri değerlendirilmeli (Rancher Manager horizontal scale planlı)
- Service mesh (Istio) zorunlu olursa Rancher'ın Istio entegrasyonu vs raw kullanım

## 6. Pratik örnek — yeni cluster ekleme

### Adım adım

```bash
# 1. Yeni cluster'da SSH
ssh ubuntu@customer-y-master.lumix.io

# 2. Rancher Manager'da:
#    UI → Cluster Management → Create → Import Existing → "Generic"
#    Cluster name: "customer-y"
#    Rancher token üretir, kubectl komutu verir

# 3. Cluster'a uygula
kubectl apply -f https://rancher.lumix.io/v3/import/abc123xyz.yaml

# 4. ~30sn sonra Rancher UI'da "customer-y" Active gözükür

# 5. ArgoCD'de cluster eklemek için:
argocd cluster add customer-y-context --name customer-y \
  --kubeconfig /tmp/customer-y-kubeconfig.yaml

# 6. ArgoCD ApplicationSet zaten yeni cluster'ı pick eder ve manifest deploy
```

### Bir cluster'ı silmek (decommission)

```
1. Internal Admin Panel → Suspend Installation (önce müşteri verisini export et)
2. ArgoCD: Application kümesini delete (resource'lar temizlenir)
3. Rancher UI: Cluster → Delete (Rancher tarafından sökülür; agent kalkar)
4. Terraform: VPS destroy
5. DNS records sil
6. Vault: cluster sırları revoke + destroy
7. Audit log: decommission event
```

## 7. Tuzaklar

- **Rancher Manager backup'sız**: Rancher Manager kendi etcd'sini kullanır; Velero veya snapshot zorunlu, yoksa tüm cluster bağlantıları kaybedebilir.
- **Token leak**: Cluster registration token uzun ömürlü değil; one-time veya kısa süreli kullan.
- **Rancher agent network policy unutmak**: Agent outbound 443 lazım; çok sıkı NetworkPolicy ile agent disconnect olur.
- **Rancher RBAC ile K8s RBAC çakışması**: Rancher project user'lara role binding atayınca, manuel `RoleBinding` ile çakışmayın.
- **Rancher UI'dan kubectl edit**: Audit'siz değişiklik; ArgoCD GitOps ile drift. Edit GitOps PR olarak yapılmalı.
- **Cluster provisioning Rancher'a bırakmak**: Lumix Terraform + Ansible primary; Rancher provisioning tutarsız sonuç verebilir.
- **Fleet ve ArgoCD aynı anda kullanmak**: Resource ownership çakışması.
- **Restricted Admin role'ü herkese vermek**: Least privilege; Cluster Owner/Member daha az.
- **Multi-tenant Rancher (müşteriye Rancher erişimi verme)**: Lumix yapmıyor — Customer Admin Panel kendi paneli. Rancher'a müşteri girmez.
- **Cluster kustomize yerine helm**: Rancher Catalog Helm chart bazlı; uyumlu kal.
- **Monitoring overlapping**: Rancher Monitoring + ArgoCD ile deploy edilen Prometheus aynı anda → resource çakışması. Birini seç.
- **Local cluster (management cluster) kendi izleme**: Rancher'ın koştuğu cluster da izlenmeli; başka bir cluster'dan veya Rancher'ın kendisinden.
- **Audit log retention kısa**: Rancher audit log retention default'u kısa; uzat veya Loki'ye ship et.

## 8. Diğer konularla ilişkisi

- [Internal Admin Panel](./internal-admin-panel) — provisioning wizard tetikleyici
- [Customer Admin Panel](./customer-admin-panel) — müşterinin görmediği panel
- [Genel Mimari](../00-overview/03-overall-architecture) — Lumix sağlayıcı tarafı
- [Container & Orchestration (tech stack §18)](../00-overview/02-technology-stack-decisions) — K3s, Rancher kararı
- [CI/CD (tech stack §19)](../00-overview/02-technology-stack-decisions) — ArgoCD GitOps
- [Infrastructure as Code (tech stack §20)](../00-overview/02-technology-stack-decisions) — Terraform + Ansible

## 9. Daha derine

- Rancher Manager docs: https://ranchermanager.docs.rancher.com/
- Rancher Architecture: https://ranchermanager.docs.rancher.com/reference-guides/rancher-manager-architecture/
- K3s + Rancher: https://docs.k3s.io/
- ArgoCD ApplicationSet: https://argo-cd.readthedocs.io/en/stable/operator-manual/applicationset/
- Velero (backup): https://velero.io/
- Search keywords:
  - `rancher multi cluster kubernetes management`
  - `rancher import existing cluster k3s`
  - `argocd vs rancher fleet gitops`
  - `rancher agent reverse tunnel`
  - `rancher rbac project namespace user role`
  - `rancher backup velero etcd`

## 10. Sözlük

- **Rancher Manager** — SUSE'nin multi-cluster K8s yönetim platformu.
- **Rancher Agent (cluster-agent)** — Müşteri cluster'ında çalışıp Rancher Manager'a geri bağlanan pod.
- **Management cluster** — Rancher Manager'ın üzerinde koştuğu Kubernetes cluster'ı (Lumix tarafı).
- **Downstream cluster** — Rancher tarafından yönetilen müşteri cluster'ı.
- **Fleet** — Rancher'ın GitOps tool'u (Lumix kullanmıyor).
- **ArgoCD** — Lumix'in tercih ettiği GitOps deployment aracı.
- **Project (Rancher)** — Bir cluster içinde namespace grubu + RBAC unit.
- **Restricted Admin** — Tüm cluster'larda admin yetkisi olan global rol.
- **Cluster Owner / Member** — Belirli cluster'da yetki seviyeleri.
- **K3s** — Hafif Kubernetes distro (Lumix VPS'lerde kullanır).
- **RKE2** — Rancher Kubernetes Engine 2 (Lumix kullanmıyor; K3s yeterli).
- **Velero** — K8s cluster backup tool'u.
- **Audit log (Rancher)** — Kim hangi cluster'da ne yaptı kaydı; super-audit'in parçası.
- **NetworkPolicy** — K8s pod-to-pod trafiği kısıtlayan deklaratif kural.
- **Catalog (Apps)** — Rancher'ın Helm chart marketplace'i.
