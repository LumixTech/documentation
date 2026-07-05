---
title: Object Storage Temelleri
description: Object storage nedir, file system'den farkı nedir, bucket/object/key kavramları, S3 API standardı ve eventual consistency.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Object storage'ı **hiç görmemiş bir geliştirici**ye, dosyaların disk üzerinde tutulmasıyla bir bucket içinde tutulması arasındaki farkı anlatır. Bucket, object, key, metadata, eventual consistency ve S3 API standardı kavramlarını sıfırdan inşa eder. Lumix'in **neden RustFS gibi S3-compatible bir object storage** seçtiğini anlamak için ilk durak burasıdır. Detaylı RustFS kararı bir sonraki sayfada.

## 1. Bu nedir? (Sıfırdan)

### 1.1. Günlük hayattan analoji

Geleneksel **dosya sistemi (file system)**, bir kütüphanedeki **klasör hiyerarşisi** gibidir: `/home/oner/belgeler/vergi/2025/ocak.pdf`. Kitabı bulmak için klasör yolunu takip edersin, ortaya çıkan dosyayı görürsün, kitap rafların arasında **fiziksel bir yer** kaplar. Klasörü yeniden adlandırırsan içindeki her şey de etkilenir.

**Object storage** ise **havaalanı bagaj sistemi** gibidir. Bagajı bırakırken bir **etiket (key)** alırsın: `LH1247-SEAT12A-BAG3`. Bagajın havaalanı altında nerede tutulduğu seni ilgilendirmez. Etiketle istediğinde geri alırsın. Sisteme yeni bagaj eklemek için raf eklemen gerekmez; arka taraf otomatik genişler. Birden fazla bagaj aynı etikete sahip olamaz.

Ya da bir **emanet kasası**: bir anahtar (key) verirsin, sandığın içine ne koyduğun (bytes) ve sandığa yapıştırdığın küçük not (metadata) seni ilgilendirir. Sandığın depoda fiziksel olarak hangi rafta olduğu görünmez.

### 1.2. Teknik tanım

Object storage, verileri **flat (düz)** bir namespace içinde **key + value + metadata** üçlüsü olarak saklayan bir depolama modelidir. Hiyerarşi yoktur — `/foo/bar/baz.txt` aslında tek bir string-key'dir, klasör değildir. Erişim **HTTP API üzerinden** yapılır, POSIX file system gibi `open/read/write/close` sistem çağrılarıyla değil.

Üç ana terim:

- **Bucket**: Object'lerin saklandığı **top-level container**. Bir bucket genelde bir uygulama veya alan adına karşılık gelir. Örnek: `lumix-files-prod-installation-123`.
- **Object**: Saklanan binary blob + metadata. Bir object'in boyutu birkaç byte'tan terabyte'a kadar olabilir.
- **Key**: Object'in bucket içindeki unique tanımlayıcısı. String'tir. `/` karakteri içerebilir ama sadece **görsel hiyerarşi** sağlar, gerçek klasör değildir.

### 1.3. Object'in anatomisi

Her object şunlardan oluşur:

| Parça | Açıklama | Örnek |
|---|---|---|
| Key | Object'i tanımlayan string | `tenant/abc-123/file/2025/11/uuid.pdf` |
| Body (payload) | Asıl binary içerik | PDF dosyasının byte'ları |
| Content-Type | MIME type metadata | `application/pdf` |
| Content-Length | Byte cinsinden boyut | `2847519` |
| ETag | İçeriğin hash'i (genelde MD5) | `"a1b2c3..."` |
| Last-Modified | Son değiştirme zamanı | ISO 8601 timestamp |
| User metadata | Custom key-value | `x-amz-meta-tenant-id: abc-123` |
| Version ID | Versioning açıksa benzersiz id | `vXyZ...` |
| Storage class | Hot/cold tier bilgisi | `STANDARD`, `GLACIER` |

## 2. Hangi problemi çözüyor?

### 2.1. Dosya sistemi neden yetmiyor?

Geleneksel POSIX file system (ext4, NTFS, XFS) tek bir sunucunun diskine bağlıdır. Bir SaaS olarak Lumix'in karşılaşacağı problemler:

- **Ölçek problemi**: Bir okulda binlerce öğrenci, her birinin onlarca dosyası = milyonlarca dosya. Tek diskin inode limiti var, dizinin içindeki dosya sayısı arttıkça `readdir()` çağrısı yavaşlar.
- **Çoğullama problemi**: 3 microservice pod'u aynı `/files` klasörünü göremez. NFS veya shared volume çözümleri network bottleneck yaratır.
- **HA problemi**: Sunucu çökerse dosyalar kaybolur. RAID + backup tek çözüm — replication otomatik değil.
- **Cost growth**: Disk dolduğunda manuel müdahale gerekir, otomatik olarak yeni node eklenmez.

### 2.2. DB'ye BLOB olarak koysak?

PostgreSQL `bytea` veya `large object` (LOB) ile dosyaları DB içinde saklayabilirsin. Ancak:

- DB dump alma çok yavaşlar.
- Connection pool bandwidth'i dosya transferiyle tükenir.
- Random read/write disk paterni DB için optimize değil.
- Backup boyutu fişeklenir.
- Streaming için ekstra logic gerekir.

Sektörde **kural**: structured metadata DB'de, blob bytes object storage'da.

### 2.3. Object storage neyi çözüyor?

- Yatay ölçek (yeni disk node eklemek = kapasite artışı)
- Built-in replication (3-way replication veya erasure coding)
- HTTP API ile çoklu pod erişimi
- Pre-signed URL ile direct upload/download (app server bandwidth'inden kurtulur)
- Lifecycle rule ile cost control
- Versioning, audit, encryption gibi enterprise feature'lar built-in

## 3. Nasıl çalışıyor? (Çalışma prensibi)

### 3.1. Yüksek seviye akış

```text
Client                  API Server               Object Storage
  │                         │                         │
  │  PUT /upload-request    │                         │
  ├────────────────────────►│                         │
  │                         │   AuthZ + metadata      │
  │                         │   create row in DB      │
  │                         │                         │
  │  pre-signed PUT URL     │                         │
  │◄────────────────────────┤                         │
  │                         │                         │
  │  PUT (binary payload)   │                         │
  ├──────────────────────────────────────────────────►│
  │                         │       200 OK            │
  │◄──────────────────────────────────────────────────┤
  │                         │                         │
  │  POST /confirm-upload   │                         │
  ├────────────────────────►│   HeadObject check      │
  │                         ├────────────────────────►│
  │                         │   metadata update       │
  │   200 OK                │                         │
  │◄────────────────────────┤                         │
```

Önemli nokta: **payload binary App Server'dan geçmez**. App sadece **yetki dağıtır**.

### 3.2. Bucket içi düzen

```text
Bucket: lumix-files-prod-installation-omer-okullari

  tenant/abc-123/file/2025/11/01HXYZ.pdf
  tenant/abc-123/file/2025/11/01HXY1.png
  tenant/def-456/file/2025/11/01HXY2.jpg
  exports/tenant-abc-123/report-2025-11.xlsx
  uploads-pending/tmp/01HXY3.bin
```

Bunlar gerçekte 5 ayrı **flat key**'dir, klasör değil. Ama prefix listing (`ListObjects prefix=tenant/abc-123/`) çağrısı bu yapıdan faydalanır.

### 3.3. S3 API standardı

Amazon Web Services 2006'da S3'ü tanıttı. API'si bir **de facto standard** haline geldi. Şu temel operasyonlar her S3-compatible storage'da aynı:

| Operasyon | Ne yapar |
|---|---|
| `PutObject` | Yeni object yükler |
| `GetObject` | Object'in içeriğini okur |
| `HeadObject` | Metadata'yı okur (içerik olmadan) |
| `DeleteObject` | Object'i siler |
| `ListObjectsV2` | Bucket'taki object'leri listeler (prefix filter ile) |
| `CreateMultipartUpload` | Büyük dosya için multipart başlatır |
| `UploadPart` | Bir part yükler |
| `CompleteMultipartUpload` | Part'ları birleştirir |
| `GeneratePresignedUrl` | Time-limited imzalı URL üretir |
| `PutBucketLifecycleConfiguration` | Lifecycle rule tanımlar |

Lumix'in tüm storage adapter'ı bu standart üzerinden konuşur. RustFS bu API'yi implement ettiği için ileride MinIO, Cloudflare R2, AWS S3 veya başka bir S3-compatible storage'a **adapter değiştirmeden** geçmek mümkün.

### 3.4. Eventual consistency

Geleneksel database'lerde "yaz, hemen oku" beklersin. Object storage **dağıtık** olduğu için bazı operasyonlarda **eventual consistency** vardır:

- Eski S3 (2020 öncesi) yeni nesneye PUT sonrası LIST gecikmeli olabilirdi.
- AWS S3 artık **strong read-after-write** consistency veriyor (2020 sonrası).
- MinIO ve RustFS **strong consistency** veriyor (single-cluster içinde).
- Versioning, replication ve cross-region scenario'larda **eventual** olabilir.

Lumix kuralı: object yüklenince **HEAD ile doğrula**, sonra metadata'yı `uploaded` durumuna geçir. "Upload tamam, kullanılabilir" cevabı ancak HEAD doğrulamasından sonra döner.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Lumix mimarisinde object storage'ın yeri

- **file-service**: Tüm storage erişimini soyutlar. Diğer servisler doğrudan storage'a konuşmaz.
- **RustFS**: Self-hosted S3-compatible storage. Her **installation** kendi RustFS cluster'ını çalıştırır (detay: bir sonraki sayfa).
- **Adapter pattern**: file-service `ObjectStoragePort` interface'i kullanır. `RustFSAdapter` default implementation. İleride MinIO/R2 adapter yazılabilir.
- **Pre-signed URL**: Tüm upload/download direct client → RustFS, app server proxy değil.

### 4.2. Bucket stratejisi

Lumix iki bucket sınıfı kullanır:

| Bucket | Amaç | Lifecycle | Versioning |
|---|---|---|---|
| `lumix-files-private` | Domain dosyaları (mesaj eki, ödev, fatura PDF, profil resmi) | Soft-delete window + version retention | Açık |
| `lumix-files-public` | Public asset (logo, favicon, branding) | Yok | Kapalı |
| `lumix-exports` | DSAR export, rapor, CSV download | 7 gün sonra otomatik expire | Kapalı |
| `lumix-uploads-pending` | Henüz onaylanmamış yüklemeler | 24 saat sonra silinir | Kapalı |

Bucket sayısı az tutulur — **bucket per tenant** ANTI-PATTERN'dir (bucket limit ve management overhead'i).

### 4.3. Key format

Lumix standart key formatı:

```text
tenant/{tenant_id}/file/{yyyy}/{mm}/{uuid_v7}.{ext}
```

Örnek: `tenant/abc-123/file/2025/11/01HXYZA0123.pdf`

- `tenant_id` prefix — listing ile tenant scope edilebilir.
- `yyyy/mm` — manuel inceleme veya forensic için tarih bazlı segmentasyon.
- `uuid_v7` — time-ordered, çakışmaz, kullanıcı orijinal adıyla karıştırılmaz.
- Extension — opsiyonel, sadece browser hint için. **Authoritative değildir**; gerçek content type metadata'da tutulur.

### 4.4. Metadata DB

`file_objects` tablosu storage'ın **business view**'udur. Storage'a doğrudan sorulmaz; her zaman önce DB sorgulanır:

```sql
CREATE TABLE file_objects (
    file_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    domain_type VARCHAR(64) NOT NULL,   -- 'message_attachment', 'invoice_pdf', 'profile_photo'
    domain_id UUID,                      -- referans entity id
    bucket VARCHAR(255) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    version_id VARCHAR(255),
    content_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum VARCHAR(255),               -- SHA-256
    status VARCHAR(32) NOT NULL,         -- 'pending_upload', 'uploaded', 'scanning', 'clean', 'infected', 'soft_deleted'
    retention_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uk_file_objects_bucket_key UNIQUE (bucket, object_key)
);

CREATE INDEX idx_file_objects_tenant ON file_objects(tenant_id);
CREATE INDEX idx_file_objects_domain ON file_objects(domain_type, domain_id);
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

| Alternatif | Neden elendi |
|---|---|
| **NFS / shared volume** | Multi-pod erişimi var ama HA zayıf, bandwidth bottleneck, K8s'te ReadWriteMany volume kıt |
| **DB BLOB (PostgreSQL bytea)** | DB backup şişer, connection pool bandwidth'i tüketir, streaming desteklenmez |
| **AWS S3 (cloud)** | Self-hosted SaaS modeliyle uyumsuz. Bazı müşteriler verinin Türkiye'de fiziksel olarak kalmasını istiyor (KVKK + data residency) |
| **MinIO** | Kuvvetli alternatif. Mevcut kararımız (RustFS) detayı için bir sonraki sayfa |
| **Ceph RADOS Gateway** | S3 API var ama operasyonel maliyet yüksek (CRUSH tuning, monitoring). Lumix boyutuna overkill |

### 5.2. Kabul ettiğimiz trade-off'lar

- **Object storage = file system değil**: `mv`, `rename`, `partial update` gibi POSIX işlemleri yok. Lumix bu kısıtla uyumlu çalışır (immutable object).
- **Network call overhead**: Her dosya işlemi HTTP. Local disk'e göre daha yavaş. Çözüm: pre-signed URL ile direct client transfer.
- **Eventual consistency edge case'leri**: HEAD ile verify, hash kontrolü, retry logic mevcut.

### 5.3. Ne değişirse kararı tekrar gözden geçiririz?

- Çoklu region'lara genişlersek **CDN + edge replication** stratejisi devreye girer.
- Müşteri base'i cloud-native olursa (AWS-hosted müşteriler), S3 native adapter eklenebilir (hâlâ aynı `ObjectStoragePort` arkasında).

## 6. Pratik örnek

### 6.1. Spring Boot adapter (S3 SDK kullanımı)

```java
package com.lumix.file.adapter.storage;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URL;
import java.time.Duration;

@Component
public class RustFsObjectStorageAdapter implements ObjectStoragePort {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final StorageProperties props;

    public RustFsObjectStorageAdapter(S3Client s3Client,
                                      S3Presigner presigner,
                                      StorageProperties props) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.props = props;
    }

    @Override
    public PresignedUploadUrl createUploadUrl(UploadRequest request) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(props.getPrivateBucket())
                .key(request.objectKey())
                .contentType(request.contentType())
                .contentLength(request.sizeBytes())
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(putRequest)
                .build();

        URL url = presigner.presignPutObject(presignRequest).url();
        return new PresignedUploadUrl(url.toString(), Duration.ofMinutes(10));
    }

    @Override
    public ObjectMetadata headObject(String bucket, String key) {
        HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());

        return new ObjectMetadata(
                response.contentLength(),
                response.contentType(),
                response.eTag(),
                response.versionId(),
                response.lastModified()
        );
    }
}
```

### 6.2. application.yml

```yaml
lumix:
  storage:
    endpoint: "https://rustfs.lumix.internal:9000"
    region: "tr-central-1"
    private-bucket: "lumix-files-private"
    public-bucket: "lumix-files-public"
    export-bucket: "lumix-exports"
    pending-bucket: "lumix-uploads-pending"
    access-key: "${RUSTFS_ACCESS_KEY}"
    secret-key: "${RUSTFS_SECRET_KEY}"
    path-style-access: true   # RustFS path-style kullanır
    presigned-url-expiry: PT10M
```

### 6.3. S3Client config

```java
@Configuration
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(StorageProperties props) {
        return S3Client.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.isPathStyleAccess())
                        .build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(StorageProperties props) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.isPathStyleAccess())
                        .build())
                .build();
    }
}
```

## 7. Dikkat edilecek tuzaklar

- **Bucket'ı public yapma**. Default private; erişim sadece pre-signed URL veya backend proxy ile.
- **Key'de PII koyma**. `tenant/abc/file/ahmet-yilmaz-tc.pdf` yerine `tenant/abc/file/uuid.pdf`. Audit log'da metadata olarak yaz.
- **Bucket per tenant ANTI-PATTERN**. Bucket sayısı ölçekte yönetilemez hale gelir. Prefix-per-tenant kullan.
- **File system semantics bekleme**. Rename yok, partial write yok, atomik move yok. İstersen copy + delete yap.
- **DB metadata olmadan dosya yükleme**. Storage'da object var ama DB'de kayıt yoksa "orphan object" oluşur. Önce DB, sonra storage.
- **Storage'ı authoritative kabul etme**. Business state DB'de. Storage failure'da DB'den ne yapılacağı bilinir.
- **Pre-signed URL'i log'lara basma**. URL kısa ömürlü olsa da, sensitive bir credential gibi davran.
- **MIME type'ı client'tan al ve doğrula**. Magic bytes ile karşılaştır (`Apache Tika` veya benzeri). User-supplied content-type'a güvenme.
- **`ListObjects` ile tenant izolasyonu yapma**. Listing prefix ile gelir ama uygulama seviyesinde RLS yoksa cross-tenant leak riski olur.
- **Eventual consistency edge case'lerini ignore etme**. Upload sonrası `HeadObject` doğrula. Versioning + replication varsa daha dikkatli.

## 8. Diğer konularla ilişkisi

- [RustFS Self-Hosted Object Storage](./rustfs-self-hosted) — Lumix'in spesifik storage seçimi
- [Pre-signed URL Akışı](./presigned-urls) — direct upload/download mekaniği
- [Lifecycle Policy](./lifecycle-policies) — retention + cost control
- [ClamAV Virus Scanning](./clamav-virus-scanning) — yüklenen dosyanın güvenlik kontrolü
- [Domain Servisleri](../01-tenancy-and-domain-model/domain-services-overview) — `file-service`'in sorumluluğu
- [Genel Mimari](../00-overview/overall-architecture) — RustFS sistem haritasında nerede

## 9. Daha derine inmek için

- AWS S3 — [API Reference](https://docs.aws.amazon.com/AmazonS3/latest/API/Welcome.html)
- AWS — [S3 Data Consistency Model](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Welcome.html#ConsistencyModel)
- MinIO — [Object Storage Overview](https://min.io/docs/minio/linux/operations/concepts/architecture.html)
- RustFS — [Documentation](https://docs.rustfs.com/)
- Araştırma keyword'leri: `object storage vs file storage`, `s3 compatible api standard`, `bucket key prefix design`, `object storage eventual consistency`

## 10. Sözlük

- **Bucket** — Object storage'daki top-level container; uygulama veya alan başına bir tane düşer.
- **Object** — Bucket içinde key + body + metadata üçlüsü.
- **Key** — Object'in bucket içindeki unique string tanımlayıcısı.
- **ETag** — Object içeriğinin hash'i; integrity ve cache validation için.
- **Content-Type** — MIME type (örn. `application/pdf`).
- **Eventual consistency** — Yazma sonrası okuma değerinin **anında** değil **zamanla** tutarlı olması.
- **Pre-signed URL** — Belirli bir operasyon (PUT/GET) için süresi sınırlı, imzalı erişim URL'i.
- **S3 API** — Amazon S3'ün de facto standart object storage HTTP API'si; birçok ürün bu API'yi konuşur.
- **Storage class** — Object'in hot/cold tier sınıflandırması (örn. `STANDARD`, `GLACIER`).
- **Versioning** — Aynı key için birden fazla version saklama özelliği.
