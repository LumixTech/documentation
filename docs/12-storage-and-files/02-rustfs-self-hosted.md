---
title: RustFS — Self-Hosted Object Storage
description: Lumix'in object storage tercihi RustFS — S3-compatible, Rust-based, self-host kurulum, cluster topology, erasure coding ve MinIO ile karşılaştırma.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Lumix'in S3-compatible object storage olarak **neden RustFS** seçtiğini, **nasıl kurulduğunu**, **cluster topology**'sini, **erasure coding** ile dayanıklılığı, **olgunluk değerlendirmesini**, **MinIO ile karşılaştırmayı** ve **adapter pattern** sayesinde gelecekte değişimin nasıl sağlanacağını anlatır. Önceki sayfa object storage temellerini verdi; bu sayfa Lumix'in spesifik teknoloji kararını netleştirir.

## 1. Bu nedir? (Sıfırdan)

### 1.1. RustFS nedir?

RustFS, **Rust** programlama diliyle yazılmış, **Apache 2.0 lisanslı**, **S3 API uyumlu** açık kaynak distributed object storage sistemidir. Tek bir binary olarak dağıtılır; cluster'lanır; commodity disk üzerinde **erasure coding** ile veri dayanıklılığı sağlar. Tasarımı **MinIO'dan ilham alır** ama Rust dilinin getirdiği bellek güvenliği, daha düşük memory footprint ve performans avantajları vardır.

### 1.2. Günlük hayattan analoji

RustFS'i **modern bir lojistik deposu** gibi düşün. Her **node** (sunucu) bir **raf sistemi**. Bir paket geldiğinde, RustFS paketi **parçalara böler** ve **birden fazla rafa** dağıtır (erasure coding). Bir raf çökse bile, geriye kalan parçalardan paket **yeniden inşa** edilebilir. Müşteri ise her zaman aynı **etiket numarası (key)** ile paketini ister; deponun iç organizasyonu görünmez.

RustFS'in MinIO'dan farkı, Rust dilini kullanmasıyla **deponun yazılım kontrolünün daha az hata yapan bir kod tabanı**na sahip olmasıdır. Go (MinIO) GC pause'ları ile karşılaşırken, Rust deterministik bellek yönetimi sağlar.

### 1.3. Temel özellikleri

| Özellik | Değer |
|---|---|
| Lisans | Apache 2.0 (permissive) |
| Dil | Rust |
| API | S3-compatible (Sigv4 imzalama) |
| Topology | Single-node, distributed multi-node, multi-site |
| Erasure coding | Reed-Solomon (örn. EC:4 = 4 data + 4 parity) |
| Versioning | Destekli |
| Lifecycle | Destekli |
| Encryption | Server-side encryption (SSE-S3), TLS in-transit |
| IAM | Built-in user/policy |
| Console UI | Web tabanlı yönetim paneli |

## 2. Hangi problemi çözüyor?

### 2.1. Self-hosted SaaS problemi

Lumix **müşteri başına ayrı installation** modeli kullanıyor (her müşteri kendi K8s cluster'ı, kendi DB, kendi storage). Cloud-managed S3 (AWS, GCS, Azure Blob) bu modelle uyumsuz:

- Müşteri Türkiye'deki bir okulsa **KVKK** veriyi yurt dışı bulutuna göndermeyi sorgulanabilir hale getirir.
- Cloud storage maliyet öngörülemez (request başına ücret, egress bandwidth).
- Cluster ile aynı VPC'de olmadığı için **latency artar**.
- Müşterinin tamamen kapalı bir network'te (on-prem) çalışma talebi karşılanmaz.

### 2.2. Mevcut self-hosted seçenekler ne çözmüyor?

- **MinIO**: Olgun, ama Mart 2025'te AGPLv3 lisans modeli ve commercial subscription değişiklikleri community'de güven sarsdı.
- **Ceph + RGW**: Çok güçlü ama operasyonel maliyet yüksek (CRUSH map, PG tuning, OSD recovery).
- **OpenIO / Riak CS**: Olgunluğunu kaybetmiş, küçük topluluk.
- **SeaweedFS**: Hızlı ama S3 compatibility eksik feature'lar var.

### 2.3. RustFS neyi getirir?

- **Apache 2.0** — tamamen permissive, ticari proje için güvenli.
- **MinIO ile API kompatibilitesi** — gerekirse migration kolay.
- **Rust** — memory safety + düşük overhead.
- **Tek binary** — kurulum basit, container'a kolay yerleşir.
- **Erasure coding default** — dayanıklılık built-in.

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Distributed mode

RustFS production deploy'unda **distributed mode** kullanılır. En az **4 node** önerilir; **erasure coding** ile veri parçalanır:

```text
                Client → S3 API (PUT object 100MB)
                              │
                              ▼
                ┌─────────────────────────────┐
                │   RustFS Cluster (EC: 4+4)   │
                ├─────────────────────────────┤
                │  Node 1: data shard 1        │
                │  Node 2: data shard 2        │
                │  Node 3: data shard 3        │
                │  Node 4: data shard 4        │
                │  Node 5: parity shard 1      │
                │  Node 6: parity shard 2      │
                │  Node 7: parity shard 3      │
                │  Node 8: parity shard 4      │
                └─────────────────────────────┘
```

**4+4 EC** demek: 8 shard üretilir; herhangi 4 tanesi yeterli object'i geri inşa etmeye. Yani **4 node aynı anda çökse** bile object kurtulur. Storage overhead: 100 MB'lık dosya disk üzerinde ~200 MB tutar (50% overhead). Tam 3x replication (200% overhead) ile karşılaştırınca daha verimli.

### 3.2. Healing ve self-recovery

Bir node yeniden ayağa kalktığında RustFS otomatik olarak **healing** başlatır: eksik shard'ları diğer node'lardan reconstruct eder. Bu işlem background'da çalışır, normal trafiği bloklamaz.

### 3.3. Object isteği akışı

```text
1. Client → RustFS Server'a S3 PUT request (Sigv4 imzalı)
2. RustFS, object'i shard'lara böler (Reed-Solomon EC)
3. Shard'ları cluster'daki node'lara dağıtır
4. Tüm shard'lar yazıldığında client'a 200 OK döner
5. ETag, version_id, checksum cevapta yer alır

GET için:
1. Client → RustFS GET request
2. RustFS yeterli sayıda shard'ı (örn. 4'ten) okur
3. Object'i memory'de reconstruct eder
4. Stream halinde client'a döner
```

### 3.4. Versioning

Versioning açık bucket'ta her PUT yeni bir **version_id** üretir. Eski version silinmez (lifecycle ile expire edilene kadar). Delete operation **delete marker** bırakır; gerçek bytes hâlâ disk'tedir. Bu sayede:

- Yanlışlıkla silinen object geri alınabilir.
- Audit trail oluşur.
- Lifecycle rule ile eski version'lar otomatik temizlenir.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Lumix topology

Her **installation** kendi RustFS cluster'ını çalıştırır:

| Müşteri boyutu | Node sayısı | EC config | Disk başına |
|---|---|---|---|
| Küçük (< 50 GB beklenen) | 4 node | EC 2+2 | 500 GB SSD |
| Orta (50-500 GB) | 4 node | EC 2+2 | 2 TB NVMe |
| Büyük (500 GB+) | 8 node | EC 4+4 | 4 TB NVMe |

**Minimum 4 node**: erasure coding için gerekli, ayrıca tek node failure'da downtime olmaz.

### 4.2. Kubernetes deployment

RustFS, K3s cluster'da **StatefulSet** olarak çalışır. Her pod kendi disk'ine sahip (PVC ile bağlı persistent volume).

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: rustfs
  namespace: storage
spec:
  serviceName: rustfs
  replicas: 4
  selector:
    matchLabels:
      app: rustfs
  template:
    metadata:
      labels:
        app: rustfs
    spec:
      containers:
      - name: rustfs
        image: rustfs/rustfs:latest
        ports:
        - containerPort: 9000  # S3 API
        - containerPort: 9001  # Console
        env:
        - name: RUSTFS_ROOT_USER
          valueFrom:
            secretKeyRef:
              name: rustfs-credentials
              key: root-user
        - name: RUSTFS_ROOT_PASSWORD
          valueFrom:
            secretKeyRef:
              name: rustfs-credentials
              key: root-password
        - name: RUSTFS_VOLUMES
          value: "http://rustfs-{0...3}.rustfs.storage.svc.cluster.local:9000/data"
        volumeMounts:
        - name: data
          mountPath: /data
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
  volumeClaimTemplates:
  - metadata:
      name: data
    spec:
      accessModes: ["ReadWriteOnce"]
      storageClassName: "fast-ssd"
      resources:
        requests:
          storage: 2Ti
```

### 4.3. Adapter pattern — değiştirilebilir storage

Lumix file-service `ObjectStoragePort` interface'i kullanır:

```java
public interface ObjectStoragePort {
    PresignedUploadUrl createUploadUrl(UploadRequest request);
    PresignedDownloadUrl createDownloadUrl(DownloadRequest request);
    ObjectMetadata headObject(String bucket, String key);
    void deleteObject(String bucket, String key);
    MultipartUploadSession startMultipartUpload(MultipartRequest request);
}
```

`RustFsObjectStorageAdapter` default implementation. İleride ihtiyaç duyulursa:
- `MinioObjectStorageAdapter`
- `AwsS3ObjectStorageAdapter`
- `CloudflareR2ObjectStorageAdapter`

eklenir, core kod değişmez. Bu **hexagonal architecture** kararının somut örneklerinden biri.

### 4.4. Monitoring

RustFS Prometheus endpoint sunar (`/metrics`). Lumix observability stack:

```yaml
# ServiceMonitor for Prometheus Operator
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: rustfs
  namespace: storage
spec:
  selector:
    matchLabels:
      app: rustfs
  endpoints:
  - port: api
    path: /metrics
    interval: 30s
```

Önemli metrikler:
- `rustfs_node_disk_used_bytes` — disk doluluk
- `rustfs_s3_requests_total{op}` — operation count
- `rustfs_s3_request_duration_seconds` — latency
- `rustfs_node_healing_active` — healing in progress
- `rustfs_node_offline` — offline node count

Grafana dashboard'unda her installation için **disk doluluk %80'i geçince alarm**.

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. MinIO ile karşılaştırma

| Kriter | RustFS | MinIO |
|---|---|---|
| Lisans | Apache 2.0 | AGPLv3 (kısıtlı) + Commercial |
| Dil | Rust | Go |
| Memory footprint | Daha düşük | Standart |
| GC pause | Yok (Rust) | Olabilir (Go GC) |
| Olgunluk | Yeni (2024+) | Çok olgun (2014+) |
| Community | Büyüyor | Büyük, aktif |
| Enterprise support | Sınırlı | Var (paid) |
| S3 compatibility | Yüksek | Çok yüksek |
| Documentation | Gelişmekte | Olgun |

**Karar dengesi**: MinIO **daha olgun** ama AGPLv3 lisansı ve commercial pivot Lumix gibi self-hosted SaaS için risk. RustFS **daha taze** ama Apache 2.0 ve aynı API ile gelecek değişim güvenliği sağlıyor. Adapter pattern sayesinde **karar değişebilir**: gerekirse MinIO adapter'a geçiş bir gün-iki günlük iş.

### 5.2. Düşünülen diğer alternatifler

| Alternatif | Neden elendi |
|---|---|
| **AWS S3 (managed)** | Self-hosted modeli bozar, KVKK + data residency riski |
| **Ceph RGW** | Operasyonel kompleksite (CRUSH map, OSD tuning) küçük installation'da overhead |
| **SeaweedFS** | S3 compatibility eksikleri, lifecycle support sınırlı |
| **OpenStack Swift** | Eski, S3 değil Swift API, sınırlı community |
| **GarageHQ** | İlginç ama RustFS daha geniş feature set |

### 5.3. Kabul ettiğimiz trade-off'lar

- **Olgunluk riski**: RustFS yeni; production'da iyi monitoring + adapter ile fallback planı tutulur.
- **Ekosistem eksikliği**: MinIO'nun client tooling'i (mc CLI) RustFS'te daha az olgun. Lumix operasyonel script'leri için workaround mevcut.
- **Topluluk küçük**: Issue açıldığında MinIO kadar hızlı cevap gelmeyebilir. Self-host olduğumuz için bug bulduğumuzda direkt kod patch edebiliriz.

### 5.4. Ne değişirse kararı tekrar gözden geçiririz?

- RustFS topluluk yavaşlarsa veya kritik bug'lar çözülmezse **MinIO adapter'a geçeriz** (1-2 gün).
- AWS-native müşteri için **AWS S3 adapter** eklenir, RustFS hala default.
- Çoklu region replication ihtiyacı çıkarsa **Ceph RGW + multi-site** değerlendirilir.

## 6. Pratik örnek

### 6.1. Bucket ve lifecycle setup script

Lumix installation seed sırasında çalışan `rustfs-bootstrap.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

RUSTFS_ENDPOINT="${RUSTFS_ENDPOINT:-http://rustfs:9000}"
RUSTFS_ALIAS="lumix"

# Configure mc-compatible CLI (RustFS supports mc)
mc alias set "$RUSTFS_ALIAS" "$RUSTFS_ENDPOINT" "$RUSTFS_ROOT_USER" "$RUSTFS_ROOT_PASSWORD"

# Create buckets
mc mb --ignore-existing "$RUSTFS_ALIAS/lumix-files-private"
mc mb --ignore-existing "$RUSTFS_ALIAS/lumix-files-public"
mc mb --ignore-existing "$RUSTFS_ALIAS/lumix-exports"
mc mb --ignore-existing "$RUSTFS_ALIAS/lumix-uploads-pending"

# Enable versioning on private
mc version enable "$RUSTFS_ALIAS/lumix-files-private"

# Lifecycle: pending uploads expire in 24h
cat > /tmp/lifecycle-pending.json <<'EOF'
{
  "Rules": [
    {
      "ID": "expire-pending-uploads",
      "Status": "Enabled",
      "Expiration": { "Days": 1 }
    }
  ]
}
EOF
mc ilm import "$RUSTFS_ALIAS/lumix-uploads-pending" < /tmp/lifecycle-pending.json

# Lifecycle: exports expire in 7 days
cat > /tmp/lifecycle-exports.json <<'EOF'
{
  "Rules": [
    {
      "ID": "expire-exports",
      "Status": "Enabled",
      "Expiration": { "Days": 7 }
    }
  ]
}
EOF
mc ilm import "$RUSTFS_ALIAS/lumix-exports" < /tmp/lifecycle-exports.json

# Restrict public bucket to anonymous read only on "public/*" prefix
mc anonymous set download "$RUSTFS_ALIAS/lumix-files-public/public"

echo "RustFS bootstrap complete."
```

### 6.2. Health check Spring Boot

```java
@Component
public class RustFsHealthIndicator implements HealthIndicator {

    private final S3Client s3Client;
    private final StorageProperties props;

    public RustFsHealthIndicator(S3Client s3Client, StorageProperties props) {
        this.s3Client = s3Client;
        this.props = props;
    }

    @Override
    public Health health() {
        try {
            HeadBucketResponse response = s3Client.headBucket(HeadBucketRequest.builder()
                    .bucket(props.getPrivateBucket())
                    .build());

            return Health.up()
                    .withDetail("endpoint", props.getEndpoint())
                    .withDetail("bucket", props.getPrivateBucket())
                    .build();
        } catch (S3Exception e) {
            return Health.down()
                    .withDetail("endpoint", props.getEndpoint())
                    .withDetail("error", e.awsErrorDetails().errorMessage())
                    .build();
        }
    }
}
```

### 6.3. RustFS service account oluşturma

Her microservice kendi credential'ı ile RustFS'e bağlanır. Root credential sadece bootstrap için.

```bash
mc admin user add "$RUSTFS_ALIAS" file-service-prod "$FILE_SVC_SECRET"

cat > /tmp/file-service-policy.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:DeleteObject",
        "s3:ListBucket",
        "s3:GetObjectVersion",
        "s3:DeleteObjectVersion"
      ],
      "Resource": [
        "arn:aws:s3:::lumix-files-private",
        "arn:aws:s3:::lumix-files-private/*",
        "arn:aws:s3:::lumix-uploads-pending",
        "arn:aws:s3:::lumix-uploads-pending/*"
      ]
    }
  ]
}
EOF
mc admin policy create "$RUSTFS_ALIAS" file-service-policy /tmp/file-service-policy.json
mc admin policy attach "$RUSTFS_ALIAS" file-service-policy --user file-service-prod
```

## 7. Dikkat edilecek tuzaklar

- **4 node altında erasure coding kullanma**. Minimum 4 node, ideal 4 veya 8.
- **Node sayısını runtime'da değiştirme**. Cluster yeniden kurulumu gerekir; planlı yapılmalı.
- **Disk dolma sınırına dikkat**. %80 doluluk = alarm. RustFS healing için boş alan ister.
- **Versioning + lifecycle uyumsuzluğunu kontrol et**. Versioning açık bucket'ta delete sadece marker bırakır; gerçek silme için lifecycle rule lazım.
- **Root credential'ı uygulamaya verme**. Servis başına IAM user + policy.
- **Path-style vs virtual-hosted style**. RustFS default path-style (`endpoint/bucket/key`). SDK config'inde `pathStyleAccessEnabled(true)` zorunlu.
- **TLS'i production'da kapatma**. mTLS veya HTTPS arkasında olmalı (Traefik ingress + cert-manager).
- **Backup unutma**. Erasure coding **disk failure** korur, **operatör hatası** korumaz. RustFS bucket replication veya `mc mirror` ile off-site backup mutlaka.
- **Restore drill yap**. Backup var demek restore çalışıyor demek değil. Quartal restore tatbikatı.
- **Olgunluk farkındalığı**. RustFS yeni; major version değişimlerinde test environment'ta sınamadan production'a geçirme.

## 8. Diğer konularla ilişkisi

- [Object Storage Temelleri](./01-object-storage-fundamentals.md) — bucket/object/key kavramları
- [Pre-signed URL Akışı](./03-presigned-urls.md) — RustFS üzerinde direct upload
- [Lifecycle Policy](./04-lifecycle-policies.md) — retention + cost control detayı
- [ClamAV Virus Scanning](./05-clamav-virus-scanning.md) — RustFS'e yüklenen dosyanın taraması
- [Teknoloji Kararları](../00-overview/02-technology-stack-decisions.md) — RustFS karar satırı
- [Genel Mimari](../00-overview/03-overall-architecture.md) — installation içinde RustFS yeri

## 9. Daha derine inmek için

- RustFS — [GitHub](https://github.com/rustfs/rustfs), [Documentation](https://docs.rustfs.com/)
- MinIO — [Distributed mode docs](https://min.io/docs/minio/linux/operations/install-deploy-manage/deploy-minio-multi-node-multi-drive.html) (RustFS benzer mantık)
- Reed-Solomon erasure coding — [Backblaze Blog](https://www.backblaze.com/blog/reed-solomon/)
- S3 Compatibility — [MinIO compatibility chart](https://min.io/docs/minio/linux/operations/s3-api-compatibility.html)
- Araştırma keyword'leri: `rustfs s3 compatible distributed`, `erasure coding object storage`, `minio vs rustfs license`, `self-hosted s3 alternative apache 2.0`

## 10. Sözlük

- **RustFS** — Rust ile yazılmış Apache 2.0 lisanslı S3-compatible object storage; Lumix tercihi.
- **Erasure coding** — Veriyi N data + M parity shard'a bölerek replication'dan daha verimli dayanıklılık sağlayan kodlama.
- **Reed-Solomon** — Erasure coding'in en yaygın matematiksel algoritması.
- **Healing** — Cluster'da eksik shard'ların geri inşa edilmesi.
- **EC 4+4** — 4 data + 4 parity shard; 4 node failure'a kadar veri kaybı yok.
- **Distributed mode** — RustFS'in çoklu node ile çalıştığı production mode.
- **StatefulSet** — Kubernetes'te kalıcı kimlikli ve persistent disk'li workload tipi.
- **Adapter pattern** — Storage gibi dış sistemi interface arkasında soyutlayan tasarım pattern'i.
- **AGPLv3** — Affero GPL; network-served yazılımlarda kaynak açma yükümlülüğü getirir.
- **mc CLI** — MinIO Client; S3-compatible storage'ları yöneten komut satırı aracı (RustFS de destekler).
