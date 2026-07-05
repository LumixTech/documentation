---
title: Velero — Kubernetes Backup ve Restore
description: Velero nedir, K8s state + PV backup, RustFS'e backup, schedule, restore drill, customer cluster recovery senaryosu.
sidebar_position: 9
---

## Bu sayfa ne anlatıyor?

K8s state (manifest'ler, ConfigMap, Secret, CRD) ve PersistentVolume verisinin **disaster recovery** için periyodik olarak yedeklenmesi şart. **Velero** Lumix'in bu işlem için seçtiği araç. Bu sayfa Velero'yu sıfırdan anlatır, **backup ne içerir ne içermez** sorusunu netleştirir, **filesystem backup (Restic/Kopia)** ile **CSI snapshot** farkını gösterir, Lumix'in **müşteri başına cluster + her cluster için RustFS'e Velero backup** modelini anlatır, **restore drill** ve **customer cluster recovery** senaryolarını detaylandırır. Hedef kitle: K8s temellerini bilen DevOps; disaster recovery (DR) konusunda kavramsal aşinalığı olan.

## 1. Bu nedir? (Sıfırdan)

**Velero**, Kubernetes cluster'larının **backup ve restore'u** için açık kaynak (VMware Tanzu, eski Heptio) araç. Üç şey yapar:
1. **K8s API kaynaklarının (yaml) backup'ı** — Deployment, Service, ConfigMap, Secret, CRD…
2. **PersistentVolume verisinin backup'ı** — CSI snapshot veya filesystem (Restic/Kopia).
3. **Cluster migration** — bir cluster'dan diğerine taşıma.

Velero **object storage'a** yedek alır (S3-compatible): AWS S3, MinIO, RustFS, GCS, Azure Blob…

### Günlük hayattan analoji

Telefonun iCloud/Google backup'ı: uygulama listesi (`K8s state`), her uygulamanın iç verisi (`PV data`). Tek tıklamayla yeni telefona restore. Velero K8s için bu deneyim.

## 2. Hangi problemi çözüyor?

K8s "stateless" gibi gözükse de gerçek hayatta:
- PostgreSQL StatefulSet'leri var (DB-per-service)
- Kafka log dizinleri PV'lerde
- RustFS PV'lerinde (kullanıcı dosyaları)
- ETCD-out-of-band data (cluster state) kritik

| Acı | Velero'sız | Velero'lı |
|---|---|---|
| Yanlışlıkla namespace silmek | Tüm cluster manuel restore | `velero restore create --from-backup ...` |
| Cluster çökmesi | Manuel kubectl get + yaml export | Tek schedule yeterli |
| Müşteri cluster'ı tamamen kaybedildi | Sıfırdan kur + her şeyi seed et (saatler/günler) | Yeni cluster'a Velero restore (dakika/saat) |
| Migration (test cluster'ında prod taklit) | Manuel | Velero backup → restore |
| Selective restore (sadece bir Deployment) | Manuel | `--include-resources Deployment --selector` |
| PV verisi | Manuel `pg_dump` + dosya kopyalama | Restic/Kopia ile cluster-wide |

### Patlamış üretim hikayesi

Bir takım, dev cluster'ında yanlışlıkla prod kubeconfig'i ile `kubectl delete namespace lumix-app` yaptı. Tüm Deployment, Service, ConfigMap silindi. PV'ler `Retain` policy olduğu için disk'te kaldı ama K8s state gitti. Velero olmasaydı: Helm release recovery + ConfigMap kayıpları + custom secret değerleri tekrar. Velero ile: `velero restore --from-backup daily-2026-05-26 --include-namespaces lumix-app` → 12 dakikada toparlandı.

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Mimari

```
┌──────────────────────────────────────────────────┐
│ Velero Pod (server, lumix-system namespace)      │
│                                                  │
│   Watch:                                         │
│     - Backup CRD                                 │
│     - Schedule CRD                               │
│     - Restore CRD                                │
│                                                  │
│   Backup flow:                                   │
│     1. K8s API list resources                    │
│     2. Filter (include/exclude namespace/kind)   │
│     3. Hook'lar (DB freeze gibi pre-backup)      │
│     4. PV verisi:                                │
│        a. CSI snapshot → snapshot CRD            │
│        b. veya Restic/Kopia ile node FS scan     │
│     5. Tar/Gzip + JSON manifest                  │
│     6. S3'e push (RustFS)                        │
└──────────────────────────────────────────────────┘
       │
       ▼
┌────────────────────────────────────────────────┐
│ Node Agent DaemonSet (her node'da)             │
│   - Restic/Kopia repository init               │
│   - Volume mount + tar                         │
└────────────────────────────────────────────────┘
```

### 3.2. Backup formatları

| Format | Avantaj | Dezavantaj |
|---|---|---|
| **CSI snapshot** | Hızlı, atomic (PV-level) | CSI driver desteklemeli, taşınabilirlik sınırlı (driver-specific) |
| **Restic/Kopia FS backup** | Cross-CSI, deduplication, incremental | Node CPU/I/O; PV mount edilebilmeli |

Lumix kararı: **Kopia** (Velero v1.13+ default). CSI snapshot CSI driver tutarlı olmadığında problemli; Kopia daha taşınabilir.

### 3.3. Velero objeleri

| Nesne | Görev |
|---|---|
| `BackupStorageLocation` | Hedef S3 bucket tanımı |
| `VolumeSnapshotLocation` | CSI snapshot tanımı (Lumix kullanmaz) |
| `Backup` | Tek backup operasyonu |
| `Schedule` | Cron schedule, periyodik backup üretir |
| `Restore` | Bir backup'tan restore operasyonu |
| `DeleteBackupRequest` | Backup silme |
| `PodVolumeBackup` (otomatik) | Restic/Kopia pod-volume backup state |
| `PodVolumeRestore` (otomatik) | Restore counterpart |

### 3.4. Backup'ın içeriği

Bir Backup şunları içerir:
- API kaynak YAML'ları (kind selection ile)
- PV data (snapshot referans veya Kopia repo path)
- Metadata (ts, version, labels, annotations)

İçermez:
- etcd raw snapshot (bu K3s tarafında ayrı; bkz. [K3s sayfası](./k3s-lightweight-k8s))
- Vault state (Vault kendi backup'ı)
- Off-cluster servisler (DNS, LB config)

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Kurulum

Her **müşteri cluster'ında** Velero kurulu. Hedef: o cluster'ın kendi RustFS bucket'ı (`lumix-backup-{installation-id}`).

```bash
# Helm chart
helm repo add vmware-tanzu https://vmware-tanzu.github.io/helm-charts
helm install velero vmware-tanzu/velero \
  --namespace velero \
  --create-namespace \
  --version 6.0.0 \
  -f values-velero.yaml
```

`values-velero.yaml`:

```yaml
image:
  repository: velero/velero
  tag: v1.13.2

configuration:
  backupStorageLocation:
    - name: rustfs-default
      provider: aws
      bucket: lumix-backup-omer-okullari
      default: true
      config:
        region: tr-istanbul
        s3ForcePathStyle: true
        s3Url: https://rustfs.omer-okullari.lumix.io
  volumeSnapshotLocation: []

  features: EnableCSI
  defaultBackupStorageLocation: rustfs-default
  defaultBackupTTL: 720h         # 30 gün
  defaultRepoMaintenanceFrequency: 168h

initContainers:
  - name: velero-plugin-for-aws
    image: velero/velero-plugin-for-aws:v1.10.0
    volumeMounts:
      - mountPath: /target
        name: plugins

credentials:
  useSecret: true
  existingSecret: rustfs-credentials  # ESO ile Vault'tan gelir

deployNodeAgent: true   # Kopia için DaemonSet
nodeAgent:
  resources:
    requests:
      cpu: 200m
      memory: 256Mi
    limits:
      cpu: 1000m
      memory: 1Gi

snapshotsEnabled: false   # CSI snapshot kullanmıyoruz

metrics:
  enabled: true
  serviceMonitor:
    enabled: true
```

### 4.2. Backup schedule

```yaml
apiVersion: velero.io/v1
kind: Schedule
metadata:
  name: lumix-daily-full
  namespace: velero
spec:
  schedule: "0 2 * * *"      # her gece 02:00 UTC
  template:
    ttl: 720h                # 30 gün
    includeClusterResources: true
    includedNamespaces:
      - lumix-app
      - lumix-data
      - lumix-observability
      - lumix-system
      - lumix-temporal
    excludedResources:
      - events
      - events.events.k8s.io
    storageLocation: rustfs-default
    defaultVolumesToFsBackup: true   # Kopia ile PV backup
    hooks:
      resources:
        - name: postgres-freeze
          includedNamespaces: [lumix-data]
          labelSelector:
            matchLabels:
              app: postgresql
          pre:
            - exec:
                command: ["/bin/sh", "-c", "psql -U postgres -c 'CHECKPOINT;'"]
                onError: Continue
                timeout: 30s
---
apiVersion: velero.io/v1
kind: Schedule
metadata:
  name: lumix-hourly-app
  namespace: velero
spec:
  schedule: "0 * * * *"      # her saat
  template:
    ttl: 72h                 # 3 gün
    includedNamespaces: [lumix-app]
    excludedResources: [events, events.events.k8s.io]
    storageLocation: rustfs-default
    defaultVolumesToFsBackup: false   # app namespace stateless
```

### 4.3. RPO / RTO hedefleri

| Bileşen | RPO | RTO | Yöntem |
|---|---|---|---|
| K8s manifest state | 1 saat | 30 dk | hourly schedule + ArgoCD reconcile |
| App pods (stateless) | N/A (GitOps) | 15 dk | ArgoCD sync |
| PostgreSQL data | 15 dk | 2 saat | PostgreSQL WAL + pg_basebackup + Velero hooks |
| Kafka logs | 1 saat | 1 saat | Velero PV backup + topic replay |
| RustFS user files | 24 saat | 4 saat | RustFS native replication + Velero PV backup |
| etcd K3s state | 6 saat | 30 dk | K3s `etcd-snapshot save` cron |

PostgreSQL data için **Velero tek başına yeterli değil**: WAL archiving + pg_basebackup ana yedek; Velero rakip değil, tamamlayıcı (PV-level disaster snapshot).

### 4.4. Restic/Kopia repository

Velero ilk backup'ta her PV için Kopia repo init eder. Repo state RustFS'te. Encryption key Velero kendisinin K8s Secret'ında tutar (`velero-credentials`). Bu secret kaybolursa repo encryption key'siz açılamaz → **disaster** restore yapılamaz.

Lumix kuralı: `velero-credentials` Secret'ı **ESO ile Vault'tan beslenir**; Vault disaster recovery planı backup'lar dahilinde.

### 4.5. Restore drill (zorunlu)

Lumix kuralı: **3 ayda bir test restore**. Test cluster'ına müşteri prod backup'ından restore. Bütünlük kontrolü:
1. `velero restore create test-drill --from-backup daily-yyyy-mm-dd`
2. Pod'lar Ready oluyor mu?
3. PostgreSQL row count'lar son backup'a uygun mu?
4. Application smoke test geçiyor mu?

Drill log'u + bulgular issue tracker'a yazılır.

### 4.6. Customer cluster recovery senaryosu

Müşteri cluster'ı tamamen kaybedildi (VPS sağlayıcısı down, disaster). Adımlar:

```
1. Terraform → yeni VPS provisioning (5 dk).
2. Ansible → OS hardening + K3s install (10 dk).
3. cert-manager + Velero bootstrap (5 dk).
4. RustFS credentials inject (Vault'tan ESO).
5. velero backup get → en son backup'ı seç.
6. velero restore create recovery-2026-05-27 --from-backup daily-2026-05-26.
   - PV verisi Kopia ile restore (en uzun adım; veri boyutuna göre).
7. ArgoCD application sync (manifest'leri tekrar reconcile).
8. DNS güncelle (yeni IP).
9. Smoke test.
```

Toplam hedef RTO: **4 saat** (orta boy müşteri).

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### Alternatifler

| Alternatif | Neden seçilmedi |
|---|---|
| **Kasten K10** | Ticari (Veeam); lisans + on-prem fiyatlandırma. |
| **Stash by AppsCode** | Ticari, kapalı kaynak component. |
| **Manuel `kubectl get ... -o yaml` + `pg_dump`** | Tutarsız, drift, hook yok. |
| **Etcd snapshot only** | Sadece K8s state; PV verisi yok. |
| **Longhorn snapshot** | CSI snapshot, taşınabilir değil. |
| **Backup-as-Code (terraform import)** | İmkansız; çalışan cluster snapshot'ı için değil. |

### Kabul ettiğimiz trade-off'lar

- **Kopia node CPU/I/O**: backup sırasında node yüklenir. Kuralı: schedule gece, off-peak.
- **Initial backup uzun**: ilk full backup büyük PV'ler için saatler. Sonraki incremental hızlı.
- **Restore drift toleransı**: backup ile restore arası app version değişebilir; ArgoCD sync drift'i kapatır.
- **Encryption key Secret bağımlılığı**: kaybolması = restore imkansız. ESO + Vault zorunlu.

### Tekrar değerlendirme tetikleyicileri

- Velero performansı PV >1TB cluster'larda yetersiz olursa → CSI snapshot + ayrı PV backup tool karışımı.
- Cross-region/cross-provider replikasyon ihtiyacı → backup destination'a ek replikasyon.

## 6. Pratik örnek

### 6.1. Manuel backup

```bash
velero backup create manual-pre-upgrade \
  --include-namespaces lumix-app,lumix-data \
  --include-cluster-resources=true \
  --default-volumes-to-fs-backup \
  --ttl 168h

# İzle
velero backup describe manual-pre-upgrade --details
velero backup logs manual-pre-upgrade
```

### 6.2. Restore

```bash
# Mevcut backup'ları listele
velero backup get

# Selective restore: sadece bir namespace
velero restore create restore-academic-2026-05-27 \
  --from-backup daily-2026-05-26 \
  --include-namespaces lumix-app \
  --selector app.kubernetes.io/name=academic-service \
  --restore-volumes=true

# Restore izle
velero restore describe restore-academic-2026-05-27 --details
velero restore logs restore-academic-2026-05-27
```

### 6.3. Hook ile PostgreSQL backup-safe

```yaml
apiVersion: velero.io/v1
kind: Backup
metadata:
  name: pre-migration
spec:
  includedNamespaces: [lumix-data]
  hooks:
    resources:
      - name: postgres-checkpoint
        includedNamespaces: [lumix-data]
        labelSelector:
          matchLabels:
            app: postgresql
        pre:
          - exec:
              container: postgres
              command: ["/bin/bash", "-c", "psql -U postgres -c 'CHECKPOINT;' && pg_basebackup -D /tmp/base -F t -X stream"]
              onError: Fail
              timeout: 5m
        post:
          - exec:
              container: postgres
              command: ["/bin/bash", "-c", "rm -rf /tmp/base"]
              onError: Continue
              timeout: 30s
```

### 6.4. BackupStorageLocation RustFS

```yaml
apiVersion: velero.io/v1
kind: BackupStorageLocation
metadata:
  name: rustfs-default
  namespace: velero
spec:
  provider: aws
  default: true
  objectStorage:
    bucket: lumix-backup-omer-okullari
    prefix: velero
  config:
    region: tr-istanbul
    s3ForcePathStyle: "true"
    s3Url: https://rustfs.omer-okullari.lumix.io
  credential:
    name: rustfs-credentials
    key: cloud
```

### 6.5. Prometheus alert örnek

```yaml
- alert: VeleroBackupFailed
  expr: increase(velero_backup_failure_total[24h]) > 0
  for: 0m
  labels:
    severity: critical
  annotations:
    summary: "Velero backup failed: {{ $labels.schedule }}"

- alert: VeleroBackupNotRunning
  expr: time() - velero_backup_last_successful_timestamp > 86400 * 2
  for: 1h
  labels:
    severity: critical
  annotations:
    summary: "Velero backup hasn't run in 2 days"
```

### 6.6. Disaster Recovery runbook (özet)

```
# 1. Hangi backup'tan restore?
velero backup get --selector velero.io/schedule-name=lumix-daily-full
# Son backup'ı not et: name + timestamp

# 2. Hedef cluster Velero installed mı?
velero version

# 3. BackupStorageLocation aynı RustFS'i gösteriyor mu?
velero backup-location get

# 4. Restore başlat
velero restore create dr-restore-$(date +%Y%m%d-%H%M) \
  --from-backup daily-2026-05-26 \
  --restore-volumes=true \
  --existing-resource-policy=update

# 5. ArgoCD sync
argocd app sync lumix-platform-omer-okullari

# 6. Smoke test
curl https://api.omer-okullari.lumix.io/api/v1/health
```

## 7. Dikkat edilecek tuzaklar

- **Backup test edilmemiş**: "backup var" ≠ "restore çalışıyor". 3 ayda bir restore drill.
- **`velero-credentials` Secret'ını backup içine almak**: bu secret'sız restore yapılamaz; circular dependency. Ayrı yedek (Vault).
- **PV backup'ı kapalı (`defaultVolumesToFsBackup: false`)** ve farkına varmamak. Bir gün PV gerekince → veri yok. PV gerekli namespace'lerde explicit `--default-volumes-to-fs-backup=true`.
- **PostgreSQL hook olmadan PV snapshot**: tutarlı olmayan disk image; restore'da DB corrupt. CHECKPOINT veya `pg_basebackup` zorunlu.
- **Çok büyük PV (>500GB) Kopia backup süresi**: gece boyu bitmez. CSI snapshot ile birleşim veya boyutu küçültme stratejisi.
- **Aynı bucket'ı iki cluster'a göstermek**: backup name çakışması, restore karışıklığı. Lumix kuralı: **bucket per cluster**.
- **TTL çok kısa**: 7 gün önce silinen namespace fark edilince restore yapılamaz. Lumix kuralı: 30 gün full, 90 gün haftalık.
- **Restore sonrası Service ClusterIP çakışması**: var olan resource ile yeni gelen aynı ClusterIP'ye sahipse fail. `--existing-resource-policy=update` veya silinip restore.
- **Cluster API version mismatch**: 1.28 cluster backup → 1.30 cluster restore. CRD farkı manifest validation hatası. Resource conversion plugin.
- **Node Agent'ın memory'si yetersiz**: büyük PV backup OOM. Resource limit'i tier'a göre ayarla.
- **Velero pod restartında devam etmeyen backup**: in-progress backup işaretli kalır. Manuel `velero backup delete` veya tamamlanmayı bekle.
- **`includeClusterResources: false`**: namespace-scoped restore'da ClusterRole, CRD eksik. Full DR için `true` zorunlu.

## 8. Diğer konularla ilişkisi

- [Kubernetes Temelleri](./kubernetes-fundamentals) — yedeklediğimiz nesneler
- [K3s](./k3s-lightweight-k8s) — etcd snapshot (Velero dışı)
- [Rancher Multi-Cluster](./rancher-multi-cluster) — rancher-backup operator (Rancher kendi state'i için)
- [RustFS](../12-storage-and-files) — Velero hedef bucket
- [PostgreSQL Backup](../database-architecture) — WAL archiving + pg_basebackup
- [Customer Onboarding Pipeline](../20-iac-provisioning/customer-onboarding-pipeline) — yeni cluster setup adımı
- [Ansible Basics](../20-iac-provisioning/ansible-basics) — Velero kurulum playbook

## 9. Daha derine inmek için

- Resmi doc: [https://velero.io/docs/](https://velero.io/docs/)
- Kopia: [https://kopia.io/](https://kopia.io/)
- Velero plugin ecosystem
- "Cloud Native Patterns" — Cornelia Davis (DR bölümü)
- Search keyword'leri: *"velero kopia vs restic"*, *"velero csi snapshot"*, *"velero hook postgres consistent backup"*, *"velero schedule selective"*
- Lumix engineering-notes referansı: `21-kubernetes-helm-infra-devops-engineering-guide.md`

## 10. Sözlük

- **Velero**: K8s backup/restore aracı (VMware Tanzu).
- **BackupStorageLocation (BSL)**: Hedef object storage tanımı.
- **VolumeSnapshotLocation (VSL)**: CSI snapshot konum tanımı (Lumix kullanmaz).
- **Schedule**: Cron tabanlı periyodik backup tanımı.
- **Backup CRD**: Tek backup operasyonu.
- **Restore CRD**: Tek restore operasyonu.
- **Kopia / Restic**: Filesystem backup motorları; Velero default Kopia (v1.13+).
- **Hook (pre/post)**: Backup öncesi/sonrası container'da komut çalıştırma.
- **RPO (Recovery Point Objective)**: Kabul edilen maksimum veri kaybı süresi.
- **RTO (Recovery Time Objective)**: Restore'un tamamlanması için hedef süre.
- **DR (Disaster Recovery)**: Felaket sonrası kurtarma planı/işlemi.
- **CSI (Container Storage Interface)**: K8s'in storage driver soyutlaması.
- **Node Agent DaemonSet**: Velero'nun her node'da çalışan, PV mount'a erişen agent'ı.
- **Restore drill**: Backup'tan test restore yapma alıştırması.
