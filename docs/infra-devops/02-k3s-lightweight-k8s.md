---
title: K3s — Hafif Kubernetes Dağıtımı
description: K3s nedir, tam K8s ile farkları, Lumix'in VPS senaryosunda K3s seçim gerekçesi, single-node ve multi-node kurulum, agent join akışı.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

K3s, Rancher (artık SUSE) tarafından geliştirilen **tek binary, üretime hazır** bir Kubernetes dağıtımıdır. Lumix'te **müşteri başına cluster** kararı, hafif ve operasyonel olarak basit bir K8s dağıtımı gerektirir; K3s tam bu boşluğu doldurur. Bu sayfa K3s'i sıfırdan anlatır, "vanilla" K8s'ten neyi farklı yaptığını gösterir, Lumix VPS senaryosunda neden K3s seçildiğini detaylandırır, single-node + multi-node kurulum, agent (worker) join ve upgrade akışlarını gerçek komutlarla anlatır. Hedef kitle: K8s temellerini bilen ([Kubernetes Temelleri](./01-kubernetes-fundamentals.md)) ama K3s'i ilk kez kuran biri.

## 1. Bu nedir? (Sıfırdan)

K3s, **CNCF sertifikalı** (yani gerçek K8s API'sını eksiksiz uygulayan) bir Kubernetes dağıtımıdır. İsim "K3s" şu yaklaşımdan gelir: K8s adı 8 harf, "5 bileşeni" çıkarıp daha hafif hale getirildiği için "K3s" denir (popüler folklor).

Pratik farkları:

- **Tek binary** (~80 MB). systemd ile kurulur, `k3s server` ve `k3s agent` komutlarıyla yönetilir.
- **etcd zorunlu değil**: tek node senaryosunda **SQLite**, HA senaryosunda **embedded etcd** veya harici DB (PostgreSQL/MySQL).
- **Gömülü bileşenler**:
  - containerd (runtime)
  - flannel (CNI, VXLAN; istersen kapatıp Calico kurabilirsin)
  - CoreDNS
  - Traefik (varsayılan IngressController; Lumix bunu **biz yöneteceğiz** diye kapatır)
  - ServiceLB (LoadBalancer implementasyonu, baremetal için)
  - local-path-provisioner (varsayılan StorageClass)
  - metrics-server
- **Düşük kaynak tüketimi**: control plane ~500 MB RAM.
- **Hızlı upgrade**: tek binary değiştir, systemd restart.
- **Air-gapped destek**: tüm container image'ları tar.gz olarak paketlenebilir, internet olmadan kurulum.

### Günlük hayattan analoji

Tam K8s = bir orkestra (ayrı şef, ayrı keman grubu, ayrı nefesliler, ayrı vurmalı). K3s = aynı orkestra ama **tek ses kabini** içinde optimize edilmiş, kurulum 5 dakikada biter, çıkardığı müzik **aynı kalite**. CNCF sertifikası: "evet, bu hâlâ K8s; testleri geçiyor" garantisi.

## 2. Hangi problemi çözüyor?

Lumix'in **müşteri başına izole installation** kararı operasyonel olarak şu yükü doğurur: **N tane K8s cluster kurmak, izlemek, upgrade etmek**. Tam K8s ile bu, müşteri başına şu maliyetleri içerir:

- 3 control-plane node + ayrı etcd + ayrı load balancer = ~6 sunucu minimum
- kubeadm + cilium + ingress + cert-manager + storage class adımlarını manuel veya kapsamlı IaC ile sırayla kurma
- Upgrade için her node'da sıralı `kubeadm upgrade` koreografisi
- Müşteri başına 4-8 GB ek RAM sadece control plane için

Müşteri 30 olduğunda bu yük katlanır. **K3s** bu acıyı şu şekilde keser:

| Acı | Tam K8s | K3s |
|---|---|---|
| Kurulum süresi | 30-60 dk (kubeadm + addons) | &lt;5 dk (tek komut) |
| Min node sayısı | 3 (HA için) | 1 (dev/küçük müşteri) veya 3 (HA) |
| Control plane RAM | ~2 GB | ~500 MB |
| State store | etcd cluster | SQLite / embedded etcd / external DB |
| Upgrade | Çok adımlı koreografi | Binary değiştir + systemd restart |
| Air-gapped kurulum | Manuel image hazırlama | Resmi paket |
| Operasyonel personel | DevOps + SRE | Tek DevOps |

### Üretim hikayesi

Bir SaaS firması müşteri başına kubeadm K8s kurmaya başladı. 12. müşteride upgrade haftası 3 günlük operasyona dönüştü, çünkü her cluster ayrı versiyonda kaldı, sıralı `kubeadm upgrade plan` her seferinde sürpriz çıkardı. K3s'e geçişle her cluster için upgrade prosedürü **5 dakikalık tek script** oldu. Lumix bu tuzağa düşmemek için doğrudan K3s seçti.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. K3s mimari farkları

```
┌─────────────────── K3s server (control plane) ───────────────────┐
│   k3s binary (single process)                                    │
│   ├── kube-apiserver       (gömülü)                              │
│   ├── kube-scheduler       (gömülü)                              │
│   ├── kube-controller-mgr  (gömülü)                              │
│   ├── kubelet (kendi)      (gömülü)                              │
│   ├── containerd           (gömülü)                              │
│   ├── kine                 (etcd shim → SQLite/Postgres adapter) │
│   ├── flannel              (CNI)                                 │
│   ├── coredns              (manifests)                           │
│   └── traefik / metrics-server (manifests, opsiyonel)            │
└──────────────────────────────────────────────────────────────────┘
                              │
                              │ websocket tunnel (k3s agent ↔ server)
                              ▼
┌─────────────────── K3s agent (worker) ──────────────────────────┐
│   k3s binary                                                    │
│   ├── kubelet                                                   │
│   ├── kube-proxy                                                │
│   └── containerd                                                │
└─────────────────────────────────────────────────────────────────┘
```

**Önemli**: API yine standart Kubernetes API. `kubectl`, Helm, ArgoCD, cert-manager — hepsi farkı görmez. Çalışma ortamı, soketler, manifestler — birebir K8s.

### 3.2. Kine (etcd shim)

Kine, etcd API'sını **SQLite / PostgreSQL / MySQL / etcd** üzerinde implemente eden bir adapter'dır. Bu sayede:
- Tek node: SQLite (default) — bir dosya yeter.
- HA (3+ server node): **embedded etcd** veya **dış PostgreSQL**.
- Çok büyük cluster: dış etcd (klasik K8s gibi).

### 3.3. Token tabanlı node-join

Server kurulduğunda iki token üretilir:
- **node-token** (`/var/lib/rancher/k3s/server/node-token`): agent'lar (worker) bununla katılır.
- **server-token**: ek server node'ları (HA) bununla katılır.

Agent join akışı:

```
1. Yeni node Ubuntu kurulur, Ansible playbook çalıştırılır.
2. k3s install script çalıştırılır:
     K3S_URL=https://server-1.lumix.local:6443 \
     K3S_TOKEN=K10xxx... \
     curl ... | sh
3. Agent k3s server'a websocket tunnel açar.
4. Server agent'ı authentication ile kabul eder.
5. Yeni node "Ready" durumuna geçer (CNI hazır olduktan sonra).
```

### 3.4. HA topolojisi

```
                     ┌─── HAProxy / kube-vip (VIP: 10.0.0.10:6443) ───┐
                     │                                                 │
                     ▼                                                 │
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐         │
│  k3s server 1   │  │  k3s server 2   │  │  k3s server 3   │  ◀─────┘
│  (embedded etcd)│  │  (embedded etcd)│  │  (embedded etcd)│
└─────────────────┘  └─────────────────┘  └─────────────────┘
        ▲                    ▲                    ▲
        │ websocket tunnel   │                    │
        └────────────────────┼────────────────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
        ┌─────▼─────┐  ┌─────▼─────┐  ┌─────▼─────┐
        │ k3s agent │  │ k3s agent │  │ k3s agent │
        │ worker-1  │  │ worker-2  │  │ worker-3  │
        └───────────┘  └───────────┘  └───────────┘
```

`kube-vip` ya da `haproxy + keepalived` ile **floating IP** kullanılır. Agent'lar bu VIP'ye bağlanır → tek server düşse trafik kopmaz.

### 3.5. Disabled component flag'leri

Lumix kararı: K3s gömülü **traefik** ve **servicelb**'yi kapatır, çünkü kendi Traefik instance'ımızı yönetiriz (bkz. [Traefik Ingress](./05-traefik-ingress.md)).

```bash
INSTALL_K3S_EXEC="server \
  --disable=traefik \
  --disable=servicelb \
  --disable=local-storage \
  --flannel-backend=none \
  --disable-network-policy \
  --cluster-cidr=10.42.0.0/16 \
  --service-cidr=10.43.0.0/16"
```

(Calico kuracaksak `flannel-backend=none` ve `disable-network-policy` ile flannel/embedded NetworkPolicy'yi devre dışı bırakırız; detay [NetworkPolicy + mTLS](./11-networkpolicy-mtls.md).)

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. K3s versiyonu

| Tarih | K3s versiyon | K8s upstream | Sebep |
|---|---|---|---|
| 2026 baseline | v1.30.x (k3s1) | 1.30 | LTS-benzeri, Spring Boot 4.x ile uyumlu, 2027 sonuna kadar bakım |

Versiyon güncelleme politikası: 6 ayda bir minor upgrade, security patch için içeride patch sprint.

### 4.2. Müşteri cluster topolojisi seçimi

| Müşteri tier | Node | Detay |
|---|---|---|
| **XS** (dev/demo, &lt;500 kullanıcı) | 1 × server (SQLite) | Single-node K3s, control-plane + worker birleşik |
| **S** (&lt;5K kullanıcı) | 1 × server + 1 × agent | Server SQLite, app yükü agent'ta |
| **M** (5-50K) | 3 × server (embedded etcd) + 2 × agent | HA control plane, ayrı worker'lar |
| **L** (&gt;50K) | 3 × server (embedded etcd) + N × agent (dedicated pool) | HA + ayrı node pool (data, app, observability) |

### 4.3. Kuruluş dizini

Standart yollar:

```
/usr/local/bin/k3s                         # binary
/etc/systemd/system/k3s.service            # systemd unit
/var/lib/rancher/k3s/                      # data root
  ├── server/                              # control plane state
  │   ├── db/                              # SQLite veya etcd snapshot'ları
  │   ├── tls/                             # CA, certs
  │   └── node-token                       # agent join token
  └── agent/                               # kubelet + containerd state
/etc/rancher/k3s/k3s.yaml                  # kubeconfig (server üzerinden)
```

`KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get nodes` direkt çalışır.

### 4.4. Lumix-spesifik kurulum opsiyonları

```bash
# Server install (Ansible role: roles/k3s-server/tasks/main.yml)
INSTALL_K3S_VERSION=v1.30.4+k3s1 \
INSTALL_K3S_EXEC="server \
  --cluster-init \
  --token=$(vault kv get -field=node_token secret/lumix/k3s/omer-okullari) \
  --tls-san=k8s-api.omer-okullari.lumix.io \
  --tls-san=10.0.0.10 \
  --disable=traefik \
  --disable=servicelb \
  --flannel-backend=none \
  --disable-network-policy \
  --node-name=omer-okullari-cp-1 \
  --node-label=lumix.io/role=control-plane \
  --node-taint=CriticalAddonsOnly=true:NoExecute \
  --kubelet-arg=eviction-hard=memory.available<200Mi,nodefs.available<10% \
  --kube-apiserver-arg=audit-log-path=/var/log/k3s-audit.log \
  --kube-apiserver-arg=audit-log-maxage=30 \
  --secrets-encryption" \
  bash /tmp/k3s-install.sh
```

Notlar:
- `--cluster-init`: ilk HA server, embedded etcd başlatır.
- `--token`: HA için sabit; Vault'ta saklanır.
- `--tls-san`: VIP ve DNS adı sertifikaya eklenir (agent buradan bağlanır).
- `--secrets-encryption`: Secret nesneleri etcd'de AES-CBC ile şifrelenir.

Ek server (2. ve 3. control plane):

```bash
INSTALL_K3S_EXEC="server \
  --server=https://10.0.0.10:6443 \
  --token=... \
  ...aynı flag'ler..."
```

Agent:

```bash
K3S_URL=https://k8s-api.omer-okullari.lumix.io:6443 \
K3S_TOKEN=$(vault kv get -field=node_token secret/lumix/k3s/omer-okullari) \
INSTALL_K3S_VERSION=v1.30.4+k3s1 \
INSTALL_K3S_EXEC="agent \
  --node-name=omer-okullari-worker-1 \
  --node-label=lumix.io/role=worker \
  --node-label=lumix.io/zone=primary" \
  bash /tmp/k3s-install.sh
```

### 4.5. Backup stratejisi

- **etcd snapshot** (HA mode): `/var/lib/rancher/k3s/server/db/snapshots/`
- K3s native: `k3s etcd-snapshot save` (her 6 saatte CronJob)
- Off-cluster: snapshot RustFS'e (S3-compatible) push edilir
- Velero ile K8s state + PV backup (bkz. [Velero](./09-velero-backup.md))

### 4.6. Upgrade akışı

Resmi sıra: önce server'lar (sıralı), sonra agent'lar.

```bash
# Server upgrade (her server için sırayla)
INSTALL_K3S_VERSION=v1.30.5+k3s1 \
  curl -sfL https://get.k3s.io | sh -

# Servis otomatik restart olur, kontrol et
systemctl status k3s
kubectl get nodes -o wide   # versiyon güncel mi?
```

Lumix'te bu süreç **Rancher System Upgrade Controller** ile otomatize edilir (`Plan` CRD; bkz. [Rancher Multi-Cluster](./04-rancher-multi-cluster.md)).

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **kubeadm (vanilla K8s)** | 3 control-plane + harici etcd + addon paketleme yükü; VPS senaryosunda overkill. |
| **k0s** (Mirantis) | Benzer hafif K8s ama ekosistem K3s kadar zengin değil (Rancher entegrasyonu özellikle). |
| **MicroK8s** (Canonical) | Snap zorunluluğu; bazı kurumsal ortamlarda yasaklı; airgap karmaşıklığı. |
| **Talos Linux** | Çok güçlü ama "OS+K8s" tek paket; standart Ubuntu üzerine kurulum yapmak isteyen Lumix'in OS bağımsızlık tercihiyle uyuşmaz. |
| **OpenShift / OKD** | Lisans, ekosistem ağırlığı, self-host basitliği bozar. |
| **EKS/GKE/AKS** | Bulut-kilit; Lumix self-host odaklı (KVKK, on-prem). |

### Kabul ettiğimiz trade-off'lar

- **Tek vendor (SUSE/Rancher) etrafında ekosistem**: ama K3s Apache 2.0; fork edilebilir.
- **SQLite single-node modu HA değil**: müşteri büyüdükçe HA'ya geçiş gerekir (planlı).
- **Default Traefik versiyonunu kullanmıyoruz**: ek karmaşıklık, ama versiyon kontrolü Lumix'te kalır.

### Tekrar değerlendirme tetikleyicileri

- 500+ node'luk cluster'a doğru gidersek → external etcd düşünülmeli.
- Multi-region (cross-DC) HA gerekirse → tek cluster yerine federation veya cluster-per-region.

## 6. Pratik örnek

### 6.1. Single-node kurulum (en sade demo)

```bash
# Ubuntu 24.04 LTS üzerinde
curl -sfL https://get.k3s.io | sh -

# Doğrula
sudo kubectl get nodes
# NAME       STATUS   ROLES                  AGE   VERSION
# ubuntu     Ready    control-plane,master   30s   v1.30.4+k3s1

# kubeconfig dışarı çıkar (geliştiricinin laptop'ı için)
sudo cat /etc/rancher/k3s/k3s.yaml
```

### 6.2. HA cluster (3 server + 2 agent) — Ansible

Inventory (`inventory/omer-okullari/hosts.yml`):

```yaml
all:
  children:
    k3s_servers:
      hosts:
        cp-1:
          ansible_host: 10.0.0.11
        cp-2:
          ansible_host: 10.0.0.12
        cp-3:
          ansible_host: 10.0.0.13
    k3s_agents:
      hosts:
        worker-1:
          ansible_host: 10.0.0.21
        worker-2:
          ansible_host: 10.0.0.22
  vars:
    k3s_version: v1.30.4+k3s1
    k3s_cluster_vip: 10.0.0.10
    k3s_api_dns: k8s-api.omer-okullari.lumix.io
    k3s_token_vault_path: secret/lumix/k3s/omer-okullari
```

Playbook (`playbooks/install-k3s.yml`):

```yaml
- name: K3s cluster kurulum
  hosts: k3s_servers
  become: true
  serial: 1       # tek tek kur (HA bootstrap için)
  roles:
    - role: k3s-server
      vars:
        cluster_init: "{{ inventory_hostname == groups['k3s_servers'][0] }}"

- name: K3s agent'ları kur
  hosts: k3s_agents
  become: true
  roles:
    - role: k3s-agent
```

Role (`roles/k3s-server/tasks/main.yml`):

```yaml
- name: Vault'tan node token çek
  ansible.builtin.set_fact:
    k3s_node_token: "{{ lookup('community.hashi_vault.vault_kv2_get', k3s_token_vault_path).secret.node_token }}"

- name: K3s install script indir
  ansible.builtin.get_url:
    url: https://get.k3s.io
    dest: /tmp/k3s-install.sh
    mode: "0755"

- name: K3s server kur (ilk server: cluster-init)
  ansible.builtin.shell: |
    INSTALL_K3S_VERSION={{ k3s_version }} \
    INSTALL_K3S_EXEC="server \
      {{ '--cluster-init' if cluster_init else '--server=https://' + k3s_cluster_vip + ':6443' }} \
      --token={{ k3s_node_token }} \
      --tls-san={{ k3s_api_dns }} \
      --tls-san={{ k3s_cluster_vip }} \
      --disable=traefik \
      --disable=servicelb \
      --flannel-backend=none \
      --disable-network-policy \
      --node-name={{ inventory_hostname }} \
      --secrets-encryption" \
    bash /tmp/k3s-install.sh
  args:
    creates: /usr/local/bin/k3s

- name: K3s service çalışıyor mu?
  ansible.builtin.systemd:
    name: k3s
    state: started
    enabled: true

- name: API hazır olmasını bekle
  ansible.builtin.uri:
    url: "https://{{ ansible_host }}:6443/healthz"
    validate_certs: false
    status_code: 200
  register: api_health
  until: api_health.status == 200
  retries: 30
  delay: 5
```

Role (`roles/k3s-agent/tasks/main.yml`):

```yaml
- name: Vault'tan node token çek
  ansible.builtin.set_fact:
    k3s_node_token: "{{ lookup('community.hashi_vault.vault_kv2_get', k3s_token_vault_path).secret.node_token }}"

- name: K3s install script indir
  ansible.builtin.get_url:
    url: https://get.k3s.io
    dest: /tmp/k3s-install.sh
    mode: "0755"

- name: K3s agent kur
  ansible.builtin.shell: |
    K3S_URL=https://{{ k3s_api_dns }}:6443 \
    K3S_TOKEN={{ k3s_node_token }} \
    INSTALL_K3S_VERSION={{ k3s_version }} \
    INSTALL_K3S_EXEC="agent \
      --node-name={{ inventory_hostname }} \
      --node-label=lumix.io/role=worker" \
    bash /tmp/k3s-install.sh
  args:
    creates: /usr/local/bin/k3s
```

### 6.3. Manuel agent ekleme (acil senaryosu)

```bash
# Mevcut server'da
NODE_TOKEN=$(sudo cat /var/lib/rancher/k3s/server/node-token)

# Yeni worker üzerinde
K3S_URL=https://k8s-api.omer-okullari.lumix.io:6443 \
K3S_TOKEN=$NODE_TOKEN \
curl -sfL https://get.k3s.io | sh -s - agent

# Kontrol
kubectl get nodes -w
```

### 6.4. etcd snapshot ve restore

```bash
# Snapshot al (manuel veya cron)
sudo k3s etcd-snapshot save --name=pre-upgrade

# Snapshot listesi
sudo k3s etcd-snapshot ls

# Restore (DİKKAT: cluster durdurulur)
sudo systemctl stop k3s
sudo k3s server --cluster-reset --cluster-reset-restore-path=/var/lib/rancher/k3s/server/db/snapshots/pre-upgrade-xxx
sudo systemctl start k3s
```

Bu prosedür **HA cluster'da diğer node'ları sırasıyla yeniden join etmeyi gerektirir**. Ön koşul: Velero ile PV backup'ı ayrı tutulmalı (etcd snapshot sadece API state'i).

### 6.5. Air-gapped kurulum (offline müşteri)

```bash
# Hazırlık (internet'li makinede)
mkdir -p k3s-airgap/{images,bin}
wget -O k3s-airgap/bin/k3s https://github.com/k3s-io/k3s/releases/download/v1.30.4%2Bk3s1/k3s
wget -O k3s-airgap/images/k3s-airgap-images-amd64.tar.zst \
     https://github.com/k3s-io/k3s/releases/download/v1.30.4%2Bk3s1/k3s-airgap-images-amd64.tar.zst
wget -O k3s-airgap/install.sh https://get.k3s.io

# Hedef sunucuya kopyala (USB / private mirror)
scp -r k3s-airgap/ root@offline-host:/opt/

# Offline host
sudo mkdir -p /var/lib/rancher/k3s/agent/images
sudo cp /opt/k3s-airgap/images/k3s-airgap-images-amd64.tar.zst /var/lib/rancher/k3s/agent/images/
sudo install -m 0755 /opt/k3s-airgap/bin/k3s /usr/local/bin/k3s
INSTALL_K3S_SKIP_DOWNLOAD=true sh /opt/k3s-airgap/install.sh
```

## 7. Dikkat edilecek tuzaklar

- **SQLite tek-node + production**: bir disk hatasında cluster ölür. Production'da minimum HA (3 server, embedded etcd).
- **Token rotasyonu unutmak**: node-token sabit ve sızdırılması cluster'a yetkili node katmaya imkan verir. Lumix kuralı: token Vault'ta, rotation playbook'u 6 ayda bir.
- **`--tls-san` eksik**: kubeconfig'in `server:` alanı production'da VIP/DNS olur; SAN'da yoksa TLS hatası alırsın. Kurulumda her zaman VIP + DNS ekle.
- **Gömülü Traefik açık + kendi Traefik kurmak**: iki Traefik aynı 80/443'ü ister, çakışma. `--disable=traefik` zorunlu.
- **Gömülü local-path StorageClass açık + production PVC**: müşteri prod'unda RWO local-path = node death = veri kaybı. Lumix kuralı: production'da `--disable=local-storage` ve uygun CSI (Longhorn/Ceph/RustFS-CSI).
- **HA kurulurken ilk server'da `--cluster-init` koymamak**: ikinci server `--server=...` ile boş etcd'ye bağlanır → bootstrap fail. İlk server **mutlaka** `--cluster-init`.
- **kubeconfig localhost olarak dağıtmak**: `/etc/rancher/k3s/k3s.yaml` içinde `127.0.0.1:6443` yazar; uzaktan kullanmadan önce VIP/DNS ile değiştirilmeli.
- **Snapshot test edilmemiş**: "snapshot var" ≠ "restore çalışıyor". Ayda bir restore drill (test cluster'ında).
- **Upgrade'i agent'tan başlatmak**: server agent'ı eski versiyonda upgrade etmez; sıra **server → agent**.
- **Disk I/O yetersiz SQLite cluster'ı**: SQLite NVMe'de tutulmalı; HDD'de etcd request latency'si patlar.
- **`/var/lib/rancher` partition'ı dolması**: containerd image cache + etcd büyür. Ayrı LVM volume + alert.
- **systemd-resolved vs CoreDNS port çakışması**: 53 dinleyen iki süreç. Çözüm: `systemd-resolved` stub disable veya CoreDNS forward.

## 8. Diğer konularla ilişkisi

- [Kubernetes Temelleri](./01-kubernetes-fundamentals.md) — Pod / Deployment / Service kavramları
- [Helm Charts](./03-helm-charts.md) — uygulamaları K3s'e deploy etmek
- [Rancher Multi-Cluster](./04-rancher-multi-cluster.md) — birden fazla K3s cluster'ını merkezden yönetmek
- [Velero Backup](./09-velero-backup.md) — K3s state + PV backup
- [Ubuntu Hardening](./10-ubuntu-server-hardening.md) — K3s'in altındaki OS sertleştirmesi
- [NetworkPolicy + mTLS](./11-networkpolicy-mtls.md) — Calico ile flannel değişimi
- [Terraform Basics](../20-iac-provisioning/01-terraform-basics.md) — VPS provisioning
- [Ansible Basics](../20-iac-provisioning/02-ansible-basics.md) — K3s install role

## 9. Daha derine inmek için

- Resmi doc: [https://docs.k3s.io/](https://docs.k3s.io/)
- K3s GitHub: [https://github.com/k3s-io/k3s](https://github.com/k3s-io/k3s)
- Rancher Academy K3s kursu
- **Production Kubernetes** — Josh Rosso (vendor-agnostic ama K3s pratiklerine uygulanır)
- Search keyword'leri: *"k3s ha embedded etcd"*, *"kine etcd shim"*, *"k3s air gapped install"*, *"system-upgrade-controller plan crd"*
- Lumix engineering-notes referansı: `21-kubernetes-helm-infra-devops-engineering-guide.md`

## 10. Sözlük

- **K3s**: Rancher tarafından geliştirilen, CNCF-sertifikalı, tek binary, hafif K8s dağıtımı.
- **Kine**: etcd API'sını SQLite/Postgres/MySQL/etcd üzerinde sunan adapter; K3s'in kalbinde.
- **Embedded etcd**: K3s'in server node'larında gömülü çalışan etcd; HA mode için.
- **node-token**: Agent'ın server'a katılmak için kullandığı sır.
- **server-token**: Yeni control-plane node'unun kümeye katılmak için kullandığı sır (HA modunda).
- **`--cluster-init`**: HA bootstrap için ilk server'da kullanılan flag.
- **`--tls-san`**: API server sertifikasına ek Subject Alternative Name eklemek.
- **`--secrets-encryption`**: etcd'deki Secret nesnelerini şifrelemek için AES-CBC anahtar yönetimini açmak.
- **flannel**: K3s'in default CNI'si (VXLAN); Lumix Calico ile değiştirir.
- **System Upgrade Controller**: Rancher'ın K3s cluster'larını CRD ile sıralı upgrade eden controller'ı.
- **air-gapped**: İnternet bağlantısı olmayan ortamda kurulum.
