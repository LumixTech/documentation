---
title: Lifecycle Policy ile Retention ve Cost Control
description: Lifecycle rule nedir, soft delete + retention window, versioning + eski version expire, incomplete multipart cleanup, tenant-spesifik retention, cost optimization.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

Lumix'in object storage'da **dosyaların ne zaman silineceğini**, **versiyon geçmişinin ne kadar tutulacağını**, **yarım kalan multipart upload'ların nasıl temizleneceğini**, **tenant-spesifik retention** politikalarını ve **cost control** mantığını anlatır. Lifecycle rule olmayan bir storage **silent cost growth**'a yol açar; bu sayfa bunun nasıl engellendiğini gösterir.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Bir **arşiv deposu** düşün. Yeni gelen kutular **birinci kata** konur (en hızlı erişim). Bir yıl sonra **bodruma** taşınır (yavaş ama ucuz). Beş yıl sonra **çöpe** atılır. Bu kararı her gün manuel veren bir depo görevlisi yok — kutuların üzerindeki tarih etiketine bakan **otomatik bir kural seti** var.

Object storage lifecycle policy tam olarak budur: object'lerin yaşına, prefix'ine, tag'ine veya version durumuna göre **otomatik geçiş veya silme** kuralları.

### 1.2. Teknik tanım

Lifecycle policy, bir bucket üzerinde tanımlı XML/JSON kurallar setidir. Her kural:

- **Scope**: hangi object'lere uygulanır (prefix, tag, version durumu)
- **Action**: ne yapılır (expire, transition, abort multipart, delete version)
- **Trigger**: ne zaman (yaş veya tarih)

S3 storage server bunları **arka planda async olarak** uygular. Genelde 24 saat içinde işlenir (gerçek zamanlı değil).

### 1.3. Ana action tipleri

| Action | Ne yapar |
|---|---|
| **Expiration** | Object'i siler (versioning yoksa hard delete; varsa delete marker bırakır) |
| **Transition** | Storage class değiştirir (örn. STANDARD → GLACIER). RustFS tek class kullanıyor, Lumix için pratik değil |
| **NoncurrentVersionExpiration** | Eski version'ları siler |
| **AbortIncompleteMultipartUpload** | Yarım kalan multipart'ları temizler |

## 2. Hangi problemi çözüyor?

### 2.1. Sessiz maliyet büyümesi

Lifecycle rule olmadan:
- Geçici export dosyaları sonsuza dek tutulur.
- Confirm edilmemiş upload'lar (pending object) disk'i yer.
- Multipart upload başlatıp tamamlamayan client'lar disk'i yer (her part disk üzerinde).
- Versioning açıkken silinen dosyaların **eski version'ları** disk'i yer; "sildim" sandığın 100 GB hala duruyor.

Pratikte 6 ayda **2x disk artışı** sürpriz değil.

### 2.2. Compliance ve retention

- KVKK: kişisel veri için **purpose-based retention**. Faturalar 10 yıl, mesaj eki 90 gün, gibi.
- Soft-delete window: kullanıcı yanlışlıkla sildiğinde geri alma fırsatı (örn. 30 gün).
- Right to be forgotten: belirli süre sonra **gerçekten** silinmiş olmalı.

### 2.3. Disk healthiness

- %80 dolu disk'te RustFS healing yavaşlar.
- Disk dolarsa write'lar başarısız olur; production outage.
- Lifecycle rule disk health'ini garantiler.

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Lifecycle engine

RustFS (ve S3) lifecycle engine'i **scheduled background scan** yapar:

```text
Her 24 saatte:
  ListObjects(bucket)
  for each object:
    eval rules:
      if rule matches:
        apply action (delete, expire version, etc.)
```

Önemli not: Lifecycle rule'lar **eventual consistent** uygulanır. "1 gün sonra silinsin" kuralı 1 gün geçtikten sonra 24 saat içinde gerçekleşir. Saniye hassasiyetinde silme isteniyorsa application kodundan tetiklenmeli.

### 3.2. Versioning + lifecycle interaction

Versioning açık bucket'ta:

```text
PUT object → v1 oluşur
PUT (aynı key) → v2 oluşur (v1 noncurrent olur)
DELETE → "delete marker" v3 oluşur (v1, v2 hâlâ disk'te)
```

Lifecycle rule olmadan eski versiyonlar **sonsuza dek** durur. Bu yüzden:

- **NoncurrentVersionExpiration**: noncurrent (eski) version'ları X gün sonra sil
- **Expiration ExpiredObjectDeleteMarker**: yetim delete marker'ları temizle

### 3.3. Incomplete multipart cleanup

Client multipart başlatır → part'ları yükler → complete çağırmadan kaybolur. Her part disk'te durur, hiçbir GET ile görünmez (henüz object oluşmadı). Tek temizleme yolu lifecycle rule.

```text
AbortIncompleteMultipartUpload: 7 days
```

### 3.4. Lifecycle rule scope filtreleme

Bir bucket'ta birden fazla rule olabilir, her biri farklı prefix/tag için:

```json
{
  "Rules": [
    {
      "ID": "soft-delete-attachments",
      "Filter": { "Prefix": "tenant/" },
      "Status": "Enabled",
      "NoncurrentVersionExpiration": { "NoncurrentDays": 30 }
    },
    {
      "ID": "abort-stale-multipart",
      "Filter": {},
      "Status": "Enabled",
      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 7 }
    }
  ]
}
```

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Bucket başına standart politika

| Bucket | Soft-delete window | Versioning | Multipart abort | Hard expire |
|---|---|---|---|---|
| `lumix-files-private` | 30 gün (noncurrent expire) | Açık | 7 gün | Yok (business kontrolünde) |
| `lumix-files-public` | Yok | Kapalı | 7 gün | Yok |
| `lumix-exports` | Yok | Kapalı | 1 gün | 7 gün sonra hard expire |
| `lumix-uploads-pending` | Yok | Kapalı | 1 gün | 1 gün sonra hard expire |

### 4.2. Soft-delete pattern

Kullanıcı dosya silmek istediğinde:

```text
1. Application: UPDATE file_objects SET status='soft_deleted', deleted_at=NOW()
2. Application: DELETE object (versioning ile delete marker oluşur)
3. Eski version'lar 30 gün disk'te kalır
4. 30 gün sonra lifecycle rule eski version'ları siler
5. Application: 30 gün sonra file_objects row'unu da temizle (compliance job)
```

Kullanıcı **30 gün içinde** "undo" tıklarsa:
```text
RustFS: list versions → restore latest non-delete-marker version
Application: UPDATE file_objects SET status='clean'
```

### 4.3. Tenant-spesifik retention

Bazı tenant'larda retention politikası farklıdır (örn. PDR verisinde daha sıkı). Lumix bunu **application-level retention** ile uygular, lifecycle rule değil:

```sql
CREATE TABLE retention_policies (
    tenant_id UUID NOT NULL,
    domain_type VARCHAR(64) NOT NULL,
    retention_days INTEGER NOT NULL,
    hard_delete_after_days INTEGER NOT NULL,
    PRIMARY KEY (tenant_id, domain_type)
);
```

Compliance-service scheduled workflow her gün çalışır:
- `retention_until < NOW()` olan file_objects'ları bul
- file-service.deleteFile() çağır → DB metadata + storage temizliği
- audit log: `file.retention_expired`

### 4.4. Lifecycle bootstrap script

Installation seed'inde her bucket için lifecycle rule kurulur:

```bash
#!/usr/bin/env bash
RUSTFS_ALIAS="lumix"

# Private files lifecycle
cat > /tmp/lc-private.json <<'EOF'
{
  "Rules": [
    {
      "ID": "expire-noncurrent-versions-30d",
      "Status": "Enabled",
      "Filter": { "Prefix": "tenant/" },
      "NoncurrentVersionExpiration": { "NoncurrentDays": 30 }
    },
    {
      "ID": "abort-incomplete-multipart-7d",
      "Status": "Enabled",
      "Filter": {},
      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 7 }
    },
    {
      "ID": "remove-expired-delete-markers",
      "Status": "Enabled",
      "Filter": {},
      "Expiration": { "ExpiredObjectDeleteMarker": true }
    }
  ]
}
EOF
mc ilm import "$RUSTFS_ALIAS/lumix-files-private" < /tmp/lc-private.json

# Exports lifecycle (hard expire 7d)
cat > /tmp/lc-exports.json <<'EOF'
{
  "Rules": [
    {
      "ID": "expire-exports-7d",
      "Status": "Enabled",
      "Filter": {},
      "Expiration": { "Days": 7 }
    },
    {
      "ID": "abort-multipart-1d",
      "Status": "Enabled",
      "Filter": {},
      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 1 }
    }
  ]
}
EOF
mc ilm import "$RUSTFS_ALIAS/lumix-exports" < /tmp/lc-exports.json

# Pending uploads lifecycle (hard expire 1d)
cat > /tmp/lc-pending.json <<'EOF'
{
  "Rules": [
    {
      "ID": "expire-pending-1d",
      "Status": "Enabled",
      "Filter": {},
      "Expiration": { "Days": 1 }
    }
  ]
}
EOF
mc ilm import "$RUSTFS_ALIAS/lumix-uploads-pending" < /tmp/lc-pending.json
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Lifecycle policy vs application-level deletion

| Yaklaşım | Avantaj | Dezavantaj |
|---|---|---|
| **Lifecycle rule** | Storage native, az kod | Saniye hassasiyeti yok, eventual |
| **Application scheduled job** | Tam kontrol, business logic | Daha çok kod, scheduler güvenilir olmalı |
| **Hibrit (Lumix tercihi)** | Mekanik temizlik storage'da, business retention app'te | İki yer takip edilmeli |

Lumix iki katmanı birlikte kullanır:
- **Mekanik cleanup** (multipart abort, noncurrent version expire, delete marker temizliği) → lifecycle rule
- **Business retention** (KVKK purpose-based, tenant-specific) → compliance-service Temporal workflow

### 5.2. Storage class transition (Glacier vs Standard)

AWS S3'te STANDARD → GLACIER geçişi maliyet düşürür. RustFS tek class kullanır; bu özellik yok. İhtiyaç olursa ileride **off-site cold storage** (örn. AWS Glacier bridge) eklenir.

### 5.3. Kabul ettiğimiz trade-off'lar

- **Eventual deletion**: "1 gün sonra silinsin" gerçekte 24-48 saat olabilir. Audit hassas senaryolarda app-side silme kullanılır.
- **Versioning overhead**: %50-100 ekstra disk. Karşılığında accidental delete recovery + audit.
- **Soft-delete window katı**: 30 gün, tenant başına değişmez (technical decision). Daha esnek istiyorsak app-side soft delete yeterli.

## 6. Pratik örnek

### 6.1. file-service soft delete flow

```java
package com.lumix.file.application;

@Service
@RequiredArgsConstructor
public class FileDeletionUseCase {

    private final FileObjectRepository repository;
    private final ObjectStoragePort storage;
    private final FileEventPublisher events;
    private final AuditLogger audit;

    @Transactional
    public void softDelete(UUID fileId, UUID actorUserId, String reason) {
        FileObject file = repository.findById(fileId)
                .orElseThrow(() -> new FileNotFoundException(fileId));

        authorize(file, actorUserId, "delete");

        if (file.status() == FileStatus.SOFT_DELETED) {
            return; // idempotent
        }

        file.softDelete(actorUserId, reason);
        repository.save(file);

        storage.deleteObject(file.bucket(), file.objectKey());

        events.publishFileDeleted(file);
        audit.log("file.soft_delete", actorUserId, fileId, reason);
    }

    @Transactional
    public void restoreSoftDeleted(UUID fileId, UUID actorUserId) {
        FileObject file = repository.findById(fileId)
                .orElseThrow(() -> new FileNotFoundException(fileId));

        if (file.status() != FileStatus.SOFT_DELETED) {
            throw new IllegalStateException("File is not soft-deleted");
        }

        Instant cutoff = file.deletedAt().plus(Duration.ofDays(30));
        if (Instant.now().isAfter(cutoff)) {
            throw new IllegalStateException("Restore window expired");
        }

        String latestVersion = storage.findLatestNonDeleteMarkerVersion(
                file.bucket(), file.objectKey());
        storage.restoreVersion(file.bucket(), file.objectKey(), latestVersion);

        file.restore(actorUserId);
        repository.save(file);

        audit.log("file.restore", actorUserId, fileId, null);
    }
}
```

### 6.2. Compliance-driven hard delete (Temporal workflow)

```java
package com.lumix.compliance.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface RetentionEnforcementWorkflow {
    @WorkflowMethod
    void enforceRetention(UUID tenantId);
}

@Component
public class RetentionEnforcementWorkflowImpl implements RetentionEnforcementWorkflow {

    private final RetentionActivities activities = Workflow.newActivityStub(
            RetentionActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public void enforceRetention(UUID tenantId) {
        List<RetentionPolicy> policies = activities.loadPolicies(tenantId);

        for (RetentionPolicy policy : policies) {
            List<UUID> expiredFileIds = activities.findExpiredFiles(
                    tenantId, policy.domainType(), policy.retentionDays());

            for (UUID fileId : expiredFileIds) {
                activities.hardDeleteFile(fileId);
            }
        }
    }
}
```

### 6.3. Monitoring metrics

Prometheus query örnekleri:

```promql
# Disk doluluk %
(rustfs_node_disk_used_bytes / rustfs_node_disk_total_bytes) * 100

# Son 24 saat içinde lifecycle ile silinen object sayısı
rate(rustfs_lifecycle_deleted_objects_total[24h]) * 86400

# Yarım kalan multipart sayısı
rustfs_multipart_incomplete_count
```

Alarm:
```yaml
- alert: RustFsDiskFull
  expr: (rustfs_node_disk_used_bytes / rustfs_node_disk_total_bytes) > 0.80
  for: 10m
  annotations:
    summary: "RustFS disk doluluğu %80'i aştı"

- alert: RustFsMultipartLeaks
  expr: rustfs_multipart_incomplete_count > 1000
  for: 1h
  annotations:
    summary: "1000+ yarım multipart upload, lifecycle çalışmıyor olabilir"
```

## 7. Dikkat edilecek tuzaklar

- **Lifecycle rule yoksa disk şişer**. Bucket oluştururken **mutlaka** lifecycle ekle (bootstrap script otomatize etsin).
- **Versioning + lifecycle eksik**. Versioning açıksa NoncurrentVersionExpiration olmalı; aksi halde "silinen" dosyalar disk'te durur.
- **Multipart abort rule unutulmasın**. Aksi halde yarım upload'lar görünmez disk yer.
- **"Days" ile "Date" karıştırma**. `Days: 7` = "yaratıldıktan 7 gün sonra". `Date: 2026-01-01` = "spesifik tarih". Karışıklık production bug'a yol açar.
- **Lifecycle test environment'ta doğrula**. Production'da "1 günden eski sil" yazıp "1 saatten eski sil" anlamına geleceğini varsayma.
- **Application metadata ile sync kal**. Storage'da silinen dosya DB'de duruyorsa orphan. Soft delete window sonunda DB row'u da temizle.
- **Filter prefix typo'su**. `tenant/` ile `tenants/` farkı tüm tenant verisini silebilir veya hiçbir şey silmeyebilir.
- **Lifecycle saniye değil gün cinsinden**. Saniye-hassas silme istenirse app-level scheduled job lazım.
- **Retention policy ile lifecycle çakışması**. Aynı dosyaya hem app retention hem lifecycle uygulanıyorsa hangisi önce çalışırsa o silinme yolu çalışır. Çakışmasınlar.
- **Compliance metadata'sını kaybetme**. Hard delete yaparken audit log'a "ne, ne zaman, neden" mutlaka kaydet.

## 8. Diğer konularla ilişkisi

- [Object Storage Temelleri](./01-object-storage-fundamentals.md)
- [RustFS Self-Hosted](./02-rustfs-self-hosted.md) — versioning + erasure coding ile lifecycle etkileşimi
- [Pre-signed URL Akışı](./03-presigned-urls.md) — multipart abort lifecycle ile bağlantı
- [ClamAV Virus Scanning](./05-clamav-virus-scanning.md) — infected dosyaların karantina + lifecycle
- [Compliance Service](../security-compliance/audit-log-design) — retention policy + DSAR
- [Workflow Temporal](../workflow-temporal) — retention enforcement workflow

## 9. Daha derine inmek için

- AWS S3 — [Lifecycle configuration elements](https://docs.aws.amazon.com/AmazonS3/latest/userguide/intro-lifecycle-rules.html)
- AWS S3 — [Lifecycle and versioning](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-and-other-bucket-config.html)
- MinIO — [Object Lifecycle Management](https://min.io/docs/minio/linux/administration/object-management/object-lifecycle-management.html)
- Araştırma keyword'leri: `s3 lifecycle policy json examples`, `s3 noncurrent version expiration`, `abort incomplete multipart upload`, `object storage retention policy kvkk`

## 10. Sözlük

- **Lifecycle policy** — Object'lere yaşa göre otomatik action uygulayan kural seti.
- **Expiration** — Object'i silen lifecycle action.
- **NoncurrentVersionExpiration** — Eski version'ları silen lifecycle action.
- **AbortIncompleteMultipartUpload** — Yarım multipart'ları temizleyen lifecycle action.
- **Delete marker** — Versioned bucket'ta silinen object'in yerine bırakılan işaret.
- **Noncurrent version** — Current olmayan, geçmiş object version'u.
- **Soft delete** — Veri hemen silinmez, "deleted" işaretlenir; belirli süre içinde geri alınabilir.
- **Hard delete** — Veri kalıcı olarak silinir; geri alınamaz.
- **Retention period** — Bir verinin tutulması gereken/zorunlu süre.
- **Storage class** — Hot/cold tier sınıflandırması (RustFS'te tek class).
